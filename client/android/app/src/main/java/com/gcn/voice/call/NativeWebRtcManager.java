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
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NativeWebRtcManager {

    public static class PeerDiagnostics {
        public String connectionState = "new";
        public String iceState = "new";
        public int rttMs = -1;
        public int jitterMs = -1;
        public int packetsLost = 0;
        public int remoteTrackCount = 0;
    }

    public interface Callback {
        void onSignal(String to, String type, Map<String, Object> payload);
        void onPeerState(String peerId, PeerDiagnostics diagnostics);
        void onError(String message);
    }

    private static final String TAG = "NativeWebRtc";

    private final Callback callback;
    private final ExecutorService rtcExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, PeerConnection> peerConnections = new HashMap<>();
    private final Map<String, PeerDiagnostics> peerDiagnostics = new HashMap<>();
    private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();

    private PeerConnectionFactory factory;
    private AudioSource localAudioSource;
    private AudioTrack localAudioTrack;
    private String localPeerId;

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

        statsExecutor.scheduleAtFixedRate(
            () -> rtcExecutor.execute(this::collectStatsForAllPeers),
            2,
            2,
            TimeUnit.SECONDS
        );
    }

    public void setMuted(boolean muted) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
        }
    }

    public void setLocalPeerId(String localPeerId) {
        rtcExecutor.execute(() -> this.localPeerId = localPeerId);
    }

    public void createOffer(String peerId) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = ensurePeerConnection(peerId);
            if (pc == null) return;
            if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
                Log.w(TAG, "Skip createOffer for " + peerId + " in state " + pc.signalingState());
                return;
            }
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
            PeerConnection.SignalingState state = pc.signalingState();
            if (state == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                Log.w(TAG, "Ignoring duplicate remote offer from " + peerId);
                return;
            }
            if (state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                || state == PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER) {
                if (!shouldAcceptCollidingOffer(peerId)) {
                    Log.w(TAG, "Offer glare with " + peerId + ", keeping local offer (impolite)");
                    return;
                }
                Log.w(TAG, "Offer glare with " + peerId + ", rolling back local offer (polite)");
                SessionDescription rollback = new SessionDescription(SessionDescription.Type.ROLLBACK, "");
                pc.setLocalDescription(new SdpAdapter() {
                    @Override
                    public void onSetSuccess() {
                        acceptRemoteOfferAndAnswer(pc, peerId, sdp);
                    }
                }, rollback);
                return;
            }
            acceptRemoteOfferAndAnswer(pc, peerId, sdp);
        });
    }

    public void handleAnswer(String peerId, Map<String, Object> payload) {
        rtcExecutor.execute(() -> {
            PeerConnection pc = peerConnections.get(peerId);
            if (pc == null) return;
            PeerConnection.SignalingState state = pc.signalingState();
            if (state != PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                && state != PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER) {
                // Duplicate/out-of-order answers happen during retries/glare; safe to ignore.
                Log.w(TAG, "Ignoring remote answer from " + peerId + " in state " + state);
                return;
            }
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
            peerDiagnostics.remove(peerId);
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
            localPeerId = null;
            peerDiagnostics.clear();
        });
        statsExecutor.shutdownNow();
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
                String connectionState;
                switch (iceConnectionState) {
                    case CONNECTED:
                    case COMPLETED:
                        connectionState = "connected";
                        break;
                    case FAILED:
                        connectionState = "failed";
                        break;
                    case DISCONNECTED:
                        connectionState = "disconnected";
                        break;
                    case CLOSED:
                        connectionState = "closed";
                        break;
                    case NEW:
                    case CHECKING:
                    default:
                        connectionState = "connecting";
                        break;
                }
                updateAndPublishPeerDiagnostics(peerId, connectionState, safeState(iceConnectionState));
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
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                PeerDiagnostics diag = getOrCreateDiagnostics(peerId);
                diag.remoteTrackCount += 1;
                callback.onPeerState(peerId, copyDiagnostics(diag));
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                updateAndPublishPeerDiagnostics(peerId, safeState(newState), currentIceState(peerId));
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
        updateAndPublishPeerDiagnostics(peerId, safeState(pc.connectionState()), safeState(pc.iceConnectionState()));
        return pc;
    }

    private void acceptRemoteOfferAndAnswer(PeerConnection pc, String peerId, String sdp) {
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
    }

    private void collectStatsForAllPeers() {
        for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
            final String peerId = entry.getKey();
            final PeerConnection pc = entry.getValue();
            if (pc == null) continue;
            pc.getStats(new RTCStatsCollectorCallback() {
                @Override
                public void onStatsDelivered(RTCStatsReport report) {
                    rtcExecutor.execute(() -> {
                        PeerDiagnostics diag = getOrCreateDiagnostics(peerId);
                        int rttMs = -1;
                        int jitterMs = -1;
                        int packetsLost = 0;
                        for (RTCStats stat : report.getStatsMap().values()) {
                            if (stat == null) continue;
                            String type = stat.getType();
                            Map<String, Object> members = stat.getMembers();
                            if ("candidate-pair".equals(type)) {
                                Object state = members.get("state");
                                if ("succeeded".equals(String.valueOf(state))) {
                                    Object rtt = members.get("currentRoundTripTime");
                                    if (rtt instanceof Number) {
                                        rttMs = (int) Math.round(((Number) rtt).doubleValue() * 1000.0);
                                    }
                                }
                            } else if ("inbound-rtp".equals(type)) {
                                Object kind = members.get("kind");
                                if (kind == null) kind = members.get("mediaType");
                                if ("audio".equals(String.valueOf(kind))) {
                                    Object jitter = members.get("jitter");
                                    if (jitter instanceof Number) {
                                        jitterMs = (int) Math.round(((Number) jitter).doubleValue() * 1000.0);
                                    }
                                    Object lost = members.get("packetsLost");
                                    if (lost instanceof Number) {
                                        packetsLost += ((Number) lost).intValue();
                                    }
                                }
                            }
                        }
                        diag.rttMs = rttMs;
                        diag.jitterMs = jitterMs;
                        diag.packetsLost = packetsLost;
                        callback.onPeerState(peerId, copyDiagnostics(diag));
                    });
                }
            });
        }
    }

    private PeerDiagnostics getOrCreateDiagnostics(String peerId) {
        PeerDiagnostics diag = peerDiagnostics.get(peerId);
        if (diag == null) {
            diag = new PeerDiagnostics();
            peerDiagnostics.put(peerId, diag);
        }
        return diag;
    }

    private void updateAndPublishPeerDiagnostics(String peerId, String connectionState, String iceState) {
        PeerDiagnostics diag = getOrCreateDiagnostics(peerId);
        diag.connectionState = connectionState;
        diag.iceState = iceState;
        callback.onPeerState(peerId, copyDiagnostics(diag));
    }

    private PeerDiagnostics copyDiagnostics(PeerDiagnostics source) {
        PeerDiagnostics copy = new PeerDiagnostics();
        copy.connectionState = source.connectionState;
        copy.iceState = source.iceState;
        copy.rttMs = source.rttMs;
        copy.jitterMs = source.jitterMs;
        copy.packetsLost = source.packetsLost;
        copy.remoteTrackCount = source.remoteTrackCount;
        return copy;
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

    private boolean shouldAcceptCollidingOffer(String peerId) {
        if (localPeerId == null || peerId == null) return true;
        // Deterministic perfect-negotiation role: lexicographically larger id is polite.
        return localPeerId.compareTo(peerId) > 0;
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
