package com.cloudwebrtc.webrtc;

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
 * FrameDecryptor attach inside the same ownership boundary as the
 * PeerConnection/RtpSender/RtpReceiver native objects. Cryptors are attached
 * once, at sender/receiver creation ({@link #attachEncryptorAtCreation}/
 * {@link #attachDecryptorAtCreation}), and never explicitly detached — tearing
 * down the PeerConnection ({@link #disposePeerConnection}) frees its
 * RtpSenders/RtpReceivers, and with them the attached cryptors. Production
 * Kayten video code must call this bridge instead of reflecting into
 * flutter_webrtc from the app plugin.</p>
 */
public final class KaytenWebRtcCryptoBridge {
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

  private static volatile KaytenVideoCryptorProvider cryptorProvider;

  public static void setCryptorProvider(KaytenVideoCryptorProvider p) { cryptorProvider = p; }
  static KaytenVideoCryptorProvider getCryptorProvider() { return cryptorProvider; }

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
      stateFor(peerConnectionId).disposing = true;
    }
  }

  static void markPeerConnectionDisposed(String peerConnectionId) {
    synchronized (lock) {
      states.remove(peerConnectionId);
    }
  }

  /**
   * Tear down a PeerConnection: mark it closing, remove the plugin's observer from its own map,
   * and drop the PcState under {@link #lock} — THEN free the native PeerConnection OUTSIDE the
   * lock.
   *
   * <p>MoHSM-321/322/347/348 UAF fix, revised for the attach-at-creation deadlock (MoHSM-321
   * follow-up). {@code attachEncryptorAtCreation}/{@code attachDecryptorAtCreation} also hold
   * {@link #lock}, so a concurrent connect-time attach is still strictly serialized against the
   * disposing-flag + observer-map-removal step above: it either runs entirely BEFORE (resolving
   * a live, non-disposing PcState) or entirely AFTER ({@code state.disposing} is already {@code
   * true}, so attach fails closed). That serialization is all the "atomicity" this method needs
   * — there is nothing to explicitly detach under the never-detach model, since {@code
   * pco.dispose()} frees the native PeerConnection along with its RtpSenders/RtpReceivers, which
   * is what actually releases the frame cryptors.
   *
   * <p>{@code pco.dispose()} is deliberately called OUTSIDE {@link #lock}. {@code
   * org.webrtc.PeerConnection.dispose()} blocks the calling (platform) thread on native teardown,
   * which itself blocks on the WebRTC signaling thread. Attach-at-creation now runs ON the
   * signaling thread ({@code PeerConnectionObserver.onAddTrack} → {@code
   * KaytenVideoCallManager.onVideoReceiverCreated}, which holds the app-level manager lock →
   * {@code attachDecryptorAtCreation} → this bridge's {@link #lock}). Holding {@link #lock}
   * across {@code pco.dispose()} would deadlock the two threads: the platform thread would sit in
   * {@code pco.dispose()} holding {@link #lock} and waiting on the signaling thread, while the
   * signaling thread sits waiting to acquire {@link #lock} while holding the manager lock — a
   * classic teardown-while-a-remote-track-arrives ANR. Doing the disposing-flag write and
   * observer-map removal under the lock FIRST, then releasing the lock before the blocking native
   * call, preserves the fail-closed guarantee for concurrent attaches without ever holding two
   * locks across a blocking cross-thread call.
   *
   * <p>The earlier by-id design (pre-attach-at-creation) additionally needed the native free
   * itself inside the critical section, because that design resolved observers by id out of a
   * shared map at attach time; freeing the PC and clearing the disposing flag outside that
   * section left a window where a re-created {@code disposing=false} PcState let an attach
   * resolve the still-mapped observer and call {@code getRtpSenderById}/{@code
   * setFrameEncryptor} on the already-freed native PeerConnection (dangling vtable → SIGSEGV in
   * {@code nativeSetFrameEncryptor}). Attach-at-creation never resolves an observer by id (the
   * MoHSM-347/348 reason for holding the lock across dispose no longer applies), so the disposing
   * flag + observer-map removal alone are sufficient to keep the native free out of the lock.
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
      }
      if (removeFromPluginMap != null) {
        removeFromPluginMap.run();
      }
      states.remove(peerConnectionId);
    }
    // Free the native PeerConnection (and its RtpSenders/Receivers) when one is present, OUTSIDE
    // `lock` — see the Javadoc above for why. A null PC here is a never-initialized / init-failed
    // observer — PeerConnectionObserver.dispose() does NOT null the field, so an already-freed PC
    // still reads non-null. Double-dispose is prevented one layer up by removing the observer
    // from the plugin map above (so a second String-path dispose finds no observer).
    if (pco != null && pco.getPeerConnection() != null) {
      pco.dispose();
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

  /**
   * Attach an (unbound) encryptor to a freshly-created sender. Called by the app
   * provider from inside PeerConnectionObserver.addTrack, ALREADY holding the
   * app manager.lock — this takes bridge.lock only (single manager->bridge order).
   */
  public static boolean attachEncryptorAtCreation(
      String peerConnectionId, RtpSender sender, FrameEncryptor encryptor) {
    if (sender == null || encryptor == null) return false;
    synchronized (lock) {
      PcState state = stateFor(peerConnectionId);
      if (state.disposing) return false;
      sender.setFrameEncryptor(encryptor);
      return true;
    }
  }

  public static boolean attachDecryptorAtCreation(
      String peerConnectionId, RtpReceiver receiver, FrameDecryptor decryptor) {
    if (receiver == null || decryptor == null) return false;
    synchronized (lock) {
      PcState state = stateFor(peerConnectionId);
      if (state.disposing) return false;
      receiver.setFrameDecryptor(decryptor);
      return true;
    }
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
  }
}
