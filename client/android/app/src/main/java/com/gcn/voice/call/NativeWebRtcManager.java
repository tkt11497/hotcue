package com.gcn.voice.call;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NativeWebRtcManager {

    public interface Callback {
        void onSignal(String to, String type, Map<String, Object> payload);
        void onPeerState(String peerId, String connectionState, String iceState);
        void onError(String message);
    }

    private static final String TAG = "NativeWebRtc";

    private final Callback callback;
    private final ExecutorService rtcExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, PeerConnection> peerConnections = new HashMap<>();
    private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();

    private PeerConnectionFactory factory;
    private AudioSource localAudioSource;
    private AudioTrack localAudioTrack;

    public NativeWebRtcManager(Callback callback) {
        this.callback = callback;
        this.iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        this.iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
    }

    public void initialize(Context context) {
        PeerConnectionFactory.InitializationOptions options =
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        MediaConstraints audioConstraints = new MediaConstraints();
        localAudioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("AUDIO_TRACK", localAudioSource);
    }

    public void setMuted(boolean muted) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
        }
    }

    public void createOffer(String peerId) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = ensurePeerConnection(peerId);
            if (pc == null) return;
            pc.createOffer(new SdpAdapter() {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    pc.setLocalDescription(new SdpAdapter(), sdp);
                    callback.onSignal(peerId, "offer", CollectionsUtil.mapOf(
                        "type", sdp.type.canonicalForm(),
                        "sdp", sdp.description
                    ));
                }
            }, new MediaConstraints());
        });
    }

    public void handleOffer(String peerId, Map<String, Object> payload) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = ensurePeerConnection(peerId);
            if (pc == null) return;
            String sdp = safeString(payload.get("sdp"));
            if (sdp == null) return;
            SessionDescription remote = new SessionDescription(SessionDescription.Type.OFFER, sdp);
            pc.setRemoteDescription(new SdpAdapter() {
                @Override
                public void onSetSuccess() {
                    pc.createAnswer(new SdpAdapter() {
                        @Override
                        public void onCreateSuccess(SessionDescription answer) {
                            pc.setLocalDescription(new SdpAdapter(), answer);
                            callback.onSignal(peerId, "answer", CollectionsUtil.mapOf(
                                "type", answer.type.canonicalForm(),
                                "sdp", answer.description
                            ));
                        }
                    }, new MediaConstraints());
                }
            }, remote);
        });
    }

    public void handleAnswer(String peerId, Map<String, Object> payload) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = peerConnections.get(peerId);
            if (pc == null) return;
            String sdp = safeString(payload.get("sdp"));
            if (sdp == null) return;
            SessionDescription remote = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
            pc.setRemoteDescription(new SdpAdapter(), remote);
        });
    }

    public void handleIceCandidate(String peerId, Map<String, Object> payload) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = peerConnections.get(peerId);
            if (pc == null) return;
            String candidate = safeString(payload.get("candidate"));
            String sdpMid = safeString(payload.get("sdpMid"));
            Integer sdpMLineIndex = safeInt(payload.get("sdpMLineIndex"));
            if (candidate == null || sdpMLineIndex == null) return;
            pc.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
        });
    }

    public void removePeer(String peerId) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = peerConnections.remove(peerId);
            if (pc != null) pc.close();
        });
    }

    public void closeAll() {
        rtcExecutor.execute(() -> {
            for (PeerConnection pc : peerConnections.values()) {
                pc.close();
            }
            peerConnections.clear();
            if (localAudioSource != null) {
                localAudioSource.dispose();
                localAudioSource = null;
            }
            if (localAudioTrack != null) {
                localAudioTrack.dispose();
                localAudioTrack = null;
            }
            if (factory != null) {
                factory.dispose();
                factory = null;
            }
        });
    }

    private PeerConnection ensurePeerConnection(String peerId) {
        PeerConnection existing = peerConnections.get(peerId);
        if (existing != null) return existing;
        if (factory == null) {
            callback.onError("WebRTC factory not initialized");
            return null;
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        PeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                callback.onPeerState(peerId, "connecting", safeState(iceConnectionState));
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("candidate", iceCandidate.sdp);
                payload.put("sdpMid", iceCandidate.sdpMid);
                payload.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                callback.onSignal(peerId, "ice-candidate", payload);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(MediaStream mediaStream) {}

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {}

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                callback.onPeerState(peerId, safeState(newState), currentIceState(peerId));
            }
        });

        if (pc == null) {
            callback.onError("Failed to create PeerConnection for " + peerId);
            return null;
        }

        if (localAudioTrack != null) {
            List<String> streamIds = new ArrayList<>();
            streamIds.add("voice-stream");
            pc.addTrack(localAudioTrack, streamIds);
        } else {
            Log.w(TAG, "Local audio track missing while creating peer " + peerId);
        }

        peerConnections.put(peerId, pc);
        callback.onPeerState(peerId, safeState(pc.connectionState()), safeState(pc.iceConnectionState()));
        return pc;
    }

    private String safeState(Enum<?> e) {
        return e == null ? "unknown" : e.name().toLowerCase();
    }

    private String currentIceState(String peerId) {
        PeerConnection current = peerConnections.get(peerId);
        if (current == null) return "unknown";
        return safeState(current.iceConnectionState());
    }

    private String safeString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private Integer safeInt(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) return ((Long) value).intValue();
        if (value instanceof Double) return ((Double) value).intValue();
        return null;
    }

    private static class SdpAdapter implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "SDP create failure: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "SDP set failure: " + s);
        }
    }
}
