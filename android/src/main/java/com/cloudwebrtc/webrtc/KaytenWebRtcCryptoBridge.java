package com.cloudwebrtc.webrtc;

import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
  private static StateProvider stateProvider;

  private KaytenWebRtcCryptoBridge() {}

  static void bind(StateProvider provider) {
    synchronized (lock) {
      stateProvider = provider;
    }
  }

  static void reset(StateProvider provider) {
    synchronized (lock) {
      if (stateProvider == provider) {
        states.clear();
        stateProvider = null;
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

  public static boolean isBound() {
    synchronized (lock) {
      return stateProvider != null;
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

  private static PeerConnectionObserver observerFor(String peerConnectionId) {
    StateProvider provider = stateProvider;
    if (provider == null) return null;
    return provider.getPeerConnectionObserver(peerConnectionId);
  }

  private static String findPeerConnectionIdForSender(String senderId) {
    StateProvider provider = stateProvider;
    if (!(provider instanceof MethodCallHandlerImpl)) return null;
    for (PeerConnectionObserver observer : ((MethodCallHandlerImpl) provider).peerConnectionObserversSnapshot()) {
      if (observer.getPeerConnection() != null && observer.getRtpSenderById(senderId) != null) {
        return observer.getId();
      }
    }
    return null;
  }

  private static String findPeerConnectionIdForReceiver(String receiverId) {
    StateProvider provider = stateProvider;
    if (!(provider instanceof MethodCallHandlerImpl)) return null;
    for (PeerConnectionObserver observer : ((MethodCallHandlerImpl) provider).peerConnectionObserversSnapshot()) {
      if (observer.getPeerConnection() != null && observer.getRtpReceiverById(receiverId) != null) {
        return observer.getId();
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
    final Map<String, RtpSender> senders = new HashMap<>();
    final Map<String, RtpReceiver> receivers = new HashMap<>();
  }
}
