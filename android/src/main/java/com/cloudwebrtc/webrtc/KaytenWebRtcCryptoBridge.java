package com.cloudwebrtc.webrtc;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.webrtc.FrameDecryptor;
import org.webrtc.FrameEncryptor;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;

/**
 * Kayten-owned frame-crypto bridge.
 *
 * <p>This class intentionally lives inside the flutter_webrtc Android plugin
 * package, next to PeerConnectionObserver. That keeps FrameEncryptor/
 * FrameDecryptor attach and detach inside the same ownership boundary as the
 * PeerConnection/RtpSender/RtpReceiver native objects. Production Kayten video
 * code must call this bridge instead of reflecting into flutter_webrtc from the
 * app plugin.</p>
 */
public final class KaytenWebRtcCryptoBridge {
  private static final String TAG = "KaytenWebRtcCryptoBridge";
  private static final Object lock = new Object();
  private static final Map<String, PcState> states = new HashMap<>();

  // MoHSM-347/348: track EVERY bound flutter_webrtc plugin handler, not just the
  // last one. The FCM background FlutterEngine (KaytenFirebaseService) runs
  // GeneratedPluginRegistrant, which registers flutter_webrtc and constructs a
  // second MethodCallHandlerImpl whose constructor calls bind(this). With a single
  // "last provider wins" reference, that empty-PeerConnectionObserver-map handler
  // would overwrite the main engine's handler here, so observerFor() resolves no
  // observer and every video attach fails (Dart logs present_but_unresolved →
  // MoHSM-171 degrade-to-voice). A call's PeerConnection lives in exactly one
  // handler; resolving it from whichever bound handler actually holds it is immune
  // to which engine attached last. Identity-keyed so distinct handler instances
  // never collide. All access is under `lock`.
  private static final Set<StateProvider> providers =
      Collections.newSetFromMap(new IdentityHashMap<>());

  private KaytenWebRtcCryptoBridge() {}

  static void bind(StateProvider provider) {
    synchronized (lock) {
      if (provider != null) {
        providers.add(provider);
      }
    }
  }

  static void reset(StateProvider provider) {
    synchronized (lock) {
      // Only fully clear the PcState map once the LAST handler unbinds; a live
      // background/main handler that remains bound must keep resolving its PCs.
      if (providers.remove(provider) && providers.isEmpty()) {
        states.clear();
      }
    }
  }

  static void markPeerConnectionClosing(String peerConnectionId) {
    synchronized (lock) {
      PcState state = stateFor(peerConnectionId);
      state.disposing = true;
      detachAllLocked(peerConnectionId, state);
    }
  }

  static void markPeerConnectionDisposed(String peerConnectionId) {
    synchronized (lock) {
      states.remove(peerConnectionId);
    }
  }

  /**
   * Atomically tear down a PeerConnection: mark it closing (detach Kayten cryptors), free the
   * native PeerConnection, remove the plugin's observer from its own map, and drop the PcState
   * — ALL while holding {@link #lock}.
   *
   * <p>MoHSM-321/322/347/348 UAF fix. {@code attachVideoEncryptor}/{@code attachVideoDecryptor}
   * also hold {@link #lock}, so a concurrent connect-time attach is now strictly serialized
   * against this teardown: it either runs entirely BEFORE (resolving a live PeerConnection) or
   * entirely AFTER (the observer is gone from the plugin map, so {@code observerFor} returns
   * null and attach fails closed). The previous code freed the native PC and cleared the
   * disposing flag OUTSIDE this critical section and removed the observer from the plugin map
   * only afterward — leaving a window in which an attach re-created a fresh {@code
   * disposing=false} PcState, resolved the still-mapped observer, and called
   * {@code getRtpSenderById}/{@code setFrameEncryptor} on the already-freed native PeerConnection
   * (dangling vtable → SIGSEGV in {@code nativeSetFrameEncryptor}).
   *
   * @param removeFromPluginMap removes the observer from the owning MethodCallHandlerImpl's
   *     {@code mPeerConnectionObservers} map; run under {@link #lock} so no attach can resolve a
   *     mid-teardown observer.
   */
  static void disposePeerConnection(
      String peerConnectionId,
      PeerConnectionObserver pco,
      Runnable removeFromPluginMap) {
    synchronized (lock) {
      PcState state = states.get(peerConnectionId);
      if (state != null) {
        state.disposing = true;
        detachAllLocked(peerConnectionId, state);
      }
      // Free the native PeerConnection (and its RtpSenders/Receivers) when one is present.
      // A null PC here is a never-initialized / init-failed observer — PeerConnectionObserver
      // .dispose() does NOT null the field, so an already-freed PC still reads non-null.
      // Double-dispose is prevented one layer up by removing the observer from the plugin map
      // (so a second String-path dispose finds no observer).
      if (pco != null && pco.getPeerConnection() != null) {
        pco.dispose();
      }
      if (removeFromPluginMap != null) {
        removeFromPluginMap.run();
      }
      states.remove(peerConnectionId);
    }
  }

  public static boolean isBound() {
    synchronized (lock) {
      return !providers.isEmpty();
    }
  }

  // @VisibleForTesting -- this bridge is a process-static singleton, so tests must
  // fully clear its bound-provider + PcState maps between runs for isolation.
  static void resetAllForTest() {
    synchronized (lock) {
      providers.clear();
      states.clear();
    }
  }

  public static boolean attachVideoEncryptor(
      String peerConnectionId,
      String senderId,
      FrameEncryptor encryptor) {
    if (encryptor == null) return false;
    synchronized (lock) {
      PcState state = stateFor(peerConnectionId);
      if (state.disposing) return false;
      PeerConnectionObserver observer = observerFor(peerConnectionId);
      if (observer == null || observer.getPeerConnection() == null) return false;
      RtpSender sender = observer.getRtpSenderById(senderId);
      if (sender == null) return false;
      sender.setFrameEncryptor(encryptor);
      state.senders.put(senderId, sender);
      return true;
    }
  }

  public static boolean attachVideoEncryptorBySenderId(String senderId, FrameEncryptor encryptor) {
    String peerConnectionId = findPeerConnectionIdForSender(senderId);
    if (peerConnectionId == null) return false;
    return attachVideoEncryptor(peerConnectionId, senderId, encryptor);
  }

  public static boolean attachVideoDecryptor(
      String peerConnectionId,
      String receiverId,
      FrameDecryptor decryptor) {
    if (decryptor == null) return false;
    synchronized (lock) {
      PcState state = stateFor(peerConnectionId);
      if (state.disposing) return false;
      PeerConnectionObserver observer = observerFor(peerConnectionId);
      if (observer == null || observer.getPeerConnection() == null) return false;
      RtpReceiver receiver = observer.getRtpReceiverById(receiverId);
      if (receiver == null) return false;
      receiver.setFrameDecryptor(decryptor);
      state.receivers.put(receiverId, receiver);
      return true;
    }
  }

  public static boolean attachVideoDecryptorByReceiverId(
      String receiverId,
      FrameDecryptor decryptor) {
    String peerConnectionId = findPeerConnectionIdForReceiver(receiverId);
    if (peerConnectionId == null) return false;
    return attachVideoDecryptor(peerConnectionId, receiverId, decryptor);
  }

  public static void detachVideoEncryptor(String peerConnectionId, String senderId) {
    synchronized (lock) {
      PcState state = states.get(peerConnectionId);
      if (state == null) return;
      RtpSender sender = state.senders.remove(senderId);
      if (sender != null && !state.disposing) {
        try {
          sender.setFrameEncryptor(null);
        } catch (RuntimeException e) {
          Log.w(TAG, "detachVideoEncryptor failed for " + peerConnectionId + "/" + senderId, e);
        }
      }
    }
  }

  public static void detachVideoEncryptorBySenderId(String senderId) {
    synchronized (lock) {
      for (Map.Entry<String, PcState> entry : states.entrySet()) {
        if (entry.getValue().senders.containsKey(senderId)) {
          detachVideoEncryptor(entry.getKey(), senderId);
          return;
        }
      }
    }
  }

  public static void detachVideoDecryptor(String peerConnectionId, String receiverId) {
    synchronized (lock) {
      PcState state = states.get(peerConnectionId);
      if (state == null) return;
      RtpReceiver receiver = state.receivers.remove(receiverId);
      if (receiver != null && !state.disposing) {
        try {
          receiver.setFrameDecryptor(null);
        } catch (RuntimeException e) {
          Log.w(TAG, "detachVideoDecryptor failed for " + peerConnectionId + "/" + receiverId, e);
        }
      }
    }
  }

  public static void detachVideoDecryptorByReceiverId(String receiverId) {
    synchronized (lock) {
      for (Map.Entry<String, PcState> entry : states.entrySet()) {
        if (entry.getValue().receivers.containsKey(receiverId)) {
          detachVideoDecryptor(entry.getKey(), receiverId);
          return;
        }
      }
    }
  }

  public static void detachVideoCryptors(String peerConnectionId) {
    synchronized (lock) {
      PcState state = states.get(peerConnectionId);
      if (state == null) return;
      detachAllLocked(peerConnectionId, state);
    }
  }

  private static void detachAllLocked(String peerConnectionId, PcState state) {
    for (RtpSender sender : new ArrayList<>(state.senders.values())) {
      try {
        sender.setFrameEncryptor(null);
      } catch (RuntimeException e) {
        Log.w(TAG, "detachAll encryptor failed for " + peerConnectionId, e);
      }
    }
    state.senders.clear();
    for (RtpReceiver receiver : new ArrayList<>(state.receivers.values())) {
      try {
        receiver.setFrameDecryptor(null);
      } catch (RuntimeException e) {
        Log.w(TAG, "detachAll decryptor failed for " + peerConnectionId, e);
      }
    }
    state.receivers.clear();
  }

  // Resolve the observer for [peerConnectionId] from whichever bound handler holds
  // it. A PC lives in exactly one handler, so the first non-null match is correct;
  // an empty background handler simply returns null and the search continues.
  // Caller must hold `lock`.
  private static PeerConnectionObserver observerFor(String peerConnectionId) {
    for (StateProvider provider : providers) {
      PeerConnectionObserver observer = provider.getPeerConnectionObserver(peerConnectionId);
      if (observer != null) {
        return observer;
      }
    }
    return null;
  }

  // Unlike observerFor (whose callers already hold `lock`), the by-id fallback
  // entry points (attachVideoEncryptorBySenderId / ...ByReceiverId) do NOT
  // synchronize, so these methods acquire `lock` themselves before iterating the
  // mutable `providers` set — a concurrent bind()/reset() (e.g. the FCM background
  // engine attaching mid-call) would otherwise fail-fast the IdentityHashMap
  // iterator. `lock` is reentrant, so the follow-on attachVideoEncryptor re-acquire
  // is safe.
  private static String findPeerConnectionIdForSender(String senderId) {
    synchronized (lock) {
      for (StateProvider provider : providers) {
        if (!(provider instanceof MethodCallHandlerImpl)) continue;
        for (PeerConnectionObserver observer : ((MethodCallHandlerImpl) provider).peerConnectionObserversSnapshot()) {
          if (observer.getPeerConnection() != null && observer.getRtpSenderById(senderId) != null) {
            return observer.getId();
          }
        }
      }
      return null;
    }
  }

  private static String findPeerConnectionIdForReceiver(String receiverId) {
    synchronized (lock) {
      for (StateProvider provider : providers) {
        if (!(provider instanceof MethodCallHandlerImpl)) continue;
        for (PeerConnectionObserver observer : ((MethodCallHandlerImpl) provider).peerConnectionObserversSnapshot()) {
          if (observer.getPeerConnection() != null && observer.getRtpReceiverById(receiverId) != null) {
            return observer.getId();
          }
        }
      }
      return null;
    }
  }

  private static PcState stateFor(String peerConnectionId) {
    PcState state = states.get(peerConnectionId);
    if (state == null) {
      state = new PcState();
      states.put(peerConnectionId, state);
    }
    return state;
  }

  private static final class PcState {
    boolean disposing;
    final Map<String, RtpSender> senders = new HashMap<>();
    final Map<String, RtpReceiver> receivers = new HashMap<>();
  }
}
