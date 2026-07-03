package com.cloudwebrtc.webrtc;

import org.webrtc.FrameDecryptor;
import org.webrtc.FrameEncryptor;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;

/**
 * App-registered factory the fork calls synchronously the instant it creates a
 * video RtpSender (in PeerConnectionObserver.addTrack, platform thread) or
 * receives one (onAddTrack, signaling thread). The implementation attaches its
 * (unbound) Kayten frame cryptor to the passed-in live object and returns
 * whether it did — so the fork can fail closed (disable the outbound track)
 * when no cryptor was attached. See spec 2026-07-03-video-attach-at-creation.
 */
public interface KaytenVideoCryptorProvider {
  boolean onVideoSenderCreated(String peerConnectionId, RtpSender sender);
  boolean onVideoReceiverCreated(String peerConnectionId, RtpReceiver receiver);
}
