package com.gcn.voice.call;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
        void onRecoveryState(String peerId, String recoveryState, String reason);
        void onPeerRecoveryFailure(String peerId, int attempts, String reason);
        void onError(String message);
    }

    private static final String TAG = "NativeWebRtc";
    private static final String CLOUDFLARE_TURN_KEY_ID = "f722e547eeec974871f4e1d371fad2b2";
    private static final String CLOUDFLARE_TURN_API_TOKEN = "303e3cbb923f4a731454838c87f961299a1a766ce915c96fad0128c97d5afad9";
    private static final long TURN_CREDENTIAL_TTL_SECONDS = 86_400L;
    private static final long TURN_CACHE_SAFETY_SECONDS = 300L;
    private static final long TURN_RETRY_INTERVAL_MS = 30_000L;
    private static final long RECONNECT_DEBOUNCE_MS = 5_000L;
    private static final long HARD_RESET_AFTER_DISCONNECT_MS = 15_000L;
    private static final int MAX_HARD_RESET_ATTEMPTS = 2;
    private static final String CLOUDFLARE_TURN_URL =
        "https://rtc.live.cloudflare.com/v1/turn/keys/%s/credentials/generate";

    private final Callback callback;
    private final ExecutorService rtcExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, PeerConnection> peerConnections = new HashMap<>();
    private final Map<String, PeerDiagnostics> peerDiagnostics = new HashMap<>();
    private final Map<String, String> peerSelectedRouteLogs = new HashMap<>();
    private final Map<String, Long> peerLastReconnectAttempt = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> disconnectRecoveryTasks = new HashMap<>();
    private final Map<String, Integer> peerHardResetAttempts = new HashMap<>();
    private final Map<String, Integer> peerGeneration = new HashMap<>();
    private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();

    private PeerConnectionFactory factory;
    private AudioSource localAudioSource;
    private AudioTrack localAudioTrack;
    private String localPeerId;
    private long turnCacheExpiryEpochMs = 0L;
    private long turnLastAttemptEpochMs = 0L;

    public NativeWebRtcManager(Callback callback) {
        this.callback = callback;
        resetIceServersToStunOnly();
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
            createOfferInternal(peerId, false);
        });
    }

    public void handleOffer(String peerId, Map<String, Object> payload) {
        rtcExecutor.execute(() -> {
            Integer remoteGeneration = parseSignalGeneration(payload);
            int currentGeneration = getPeerGeneration(peerId);
            if (remoteGeneration != null && remoteGeneration < currentGeneration) {
                Log.w(
                    TAG,
                    "Ignoring stale remote offer from " + peerId
                        + " gen=" + remoteGeneration + " currentGen=" + currentGeneration
                );
                return;
            }
            if (remoteGeneration != null && remoteGeneration > currentGeneration) {
                Log.i(
                    TAG,
                    "Remote offer from " + peerId + " has newer generation " + remoteGeneration
                        + " > " + currentGeneration + ", resetting peer connection"
                );
                peerGeneration.put(peerId, remoteGeneration);
                forceReplacePeerConnection(peerId, "newer-remote-offer");
            }
            final int acceptedGeneration = getPeerGeneration(peerId);
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
                        acceptRemoteOfferAndAnswer(pc, peerId, sdp, acceptedGeneration);
                    }
                }, rollback);
                return;
            }
            acceptRemoteOfferAndAnswer(pc, peerId, sdp, acceptedGeneration);
        });
    }

    public void handleAnswer(String peerId, Map<String, Object> payload) {
        rtcExecutor.execute(() -> {
            Integer remoteGeneration = parseSignalGeneration(payload);
            int currentGeneration = getPeerGeneration(peerId);
            if (remoteGeneration != null && remoteGeneration < currentGeneration) {
                Log.w(
                    TAG,
                    "Ignoring stale remote answer from " + peerId
                        + " gen=" + remoteGeneration + " currentGen=" + currentGeneration
                );
                return;
            }
            if (remoteGeneration != null && remoteGeneration > currentGeneration) {
                Log.w(
                    TAG,
                    "Ignoring out-of-sync remote answer from " + peerId
                        + " gen=" + remoteGeneration + " currentGen=" + currentGeneration
                );
                return;
            }
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
            Integer remoteGeneration = parseSignalGeneration(payload);
            int currentGeneration = getPeerGeneration(peerId);
            if (remoteGeneration != null && remoteGeneration < currentGeneration) {
                Log.w(
                    TAG,
                    "Ignoring stale remote ICE from " + peerId
                        + " gen=" + remoteGeneration + " currentGen=" + currentGeneration
                );
                return;
            }
            if (remoteGeneration != null && remoteGeneration > currentGeneration) {
                Log.w(
                    TAG,
                    "Ignoring out-of-sync remote ICE from " + peerId
                        + " gen=" + remoteGeneration + " currentGen=" + currentGeneration
                );
                return;
            }
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
            cancelDisconnectRecoveryTask(peerId);
            PeerConnection pc = peerConnections.remove(peerId);
            if (pc != null) pc.close();
            peerDiagnostics.remove(peerId);
            peerSelectedRouteLogs.remove(peerId);
            peerLastReconnectAttempt.remove(peerId);
            peerHardResetAttempts.remove(peerId);
            peerGeneration.remove(peerId);
        });
    }

    public void closeAll() {
        rtcExecutor.execute(() -> {
            for (ScheduledFuture<?> task : disconnectRecoveryTasks.values()) {
                if (task != null) task.cancel(false);
            }
            disconnectRecoveryTasks.clear();
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
            peerSelectedRouteLogs.clear();
            peerLastReconnectAttempt.clear();
            peerHardResetAttempts.clear();
            peerGeneration.clear();
        });
        statsExecutor.shutdownNow();
        reconnectExecutor.shutdownNow();
    }

    public void resetPeerConnections() {
        rtcExecutor.execute(() -> {
            for (ScheduledFuture<?> task : disconnectRecoveryTasks.values()) {
                if (task != null) task.cancel(false);
            }
            disconnectRecoveryTasks.clear();
            for (PeerConnection pc : peerConnections.values()) {
                pc.close();
            }
            peerConnections.clear();
            peerDiagnostics.clear();
            peerSelectedRouteLogs.clear();
            peerLastReconnectAttempt.clear();
            peerHardResetAttempts.clear();
        });
    }

    private PeerConnection ensurePeerConnection(String peerId) {
        PeerConnection existing = peerConnections.get(peerId);
        if (existing != null) return existing;
        if (factory == null) {
            callback.onError("WebRTC factory not initialized");
            return null;
        }

        updateIceServersIfNeeded();
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
                        peerLastReconnectAttempt.remove(peerId);
                        peerHardResetAttempts.remove(peerId);
                        cancelDisconnectRecoveryTask(peerId);
                        callback.onRecoveryState(peerId, "idle", "ice-connected");
                        Log.i(TAG, "Peer " + peerId + " ICE connected (" + iceConnectionState + ")");
                        break;
                    case FAILED:
                        connectionState = "failed";
                        maybeAttemptReconnect(peerId, "ice-failed");
                        scheduleHardReset(peerId, "ice-failed");
                        Log.w(TAG, "Peer " + peerId + " ICE failed");
                        break;
                    case DISCONNECTED:
                        connectionState = "disconnected";
                        maybeAttemptReconnect(peerId, "ice-disconnected");
                        scheduleHardReset(peerId, "ice-disconnected");
                        Log.w(TAG, "Peer " + peerId + " ICE disconnected");
                        break;
                    case CLOSED:
                        connectionState = "closed";
                        scheduleHardReset(peerId, "ice-closed");
                        Log.i(TAG, "Peer " + peerId + " ICE closed");
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
                payload.put("gen", getPeerGeneration(peerId));
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

        if (!peerGeneration.containsKey(peerId)) {
            peerGeneration.put(peerId, 1);
        }
        peerConnections.put(peerId, pc);
        updateAndPublishPeerDiagnostics(peerId, safeState(pc.connectionState()), safeState(pc.iceConnectionState()));
        return pc;
    }

    private void acceptRemoteOfferAndAnswer(PeerConnection pc, String peerId, String sdp, int generation) {
        SessionDescription remote = new SessionDescription(SessionDescription.Type.OFFER, sdp);
        pc.setRemoteDescription(new SdpAdapter() {
            @Override
            public void onSetSuccess() {
                pc.createAnswer(new SdpAdapter() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        pc.setLocalDescription(new SdpAdapter(), answer);
                        callback.onSignal(
                            peerId,
                            "answer",
                            CollectionsUtil.mapOf(
                                "type", answer.type.canonicalForm(),
                                "sdp", answer.description,
                                "gen", generation
                            )
                        );
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
                        String selectedLocalCandidateId = null;
                        String selectedRemoteCandidateId = null;
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
                                boolean selected = false;
                                Object selectedObj = members.get("selected");
                                Object nominatedObj = members.get("nominated");
                                if (selectedObj instanceof Boolean && (Boolean) selectedObj) selected = true;
                                if (nominatedObj instanceof Boolean && (Boolean) nominatedObj) selected = true;
                                if (selected || "succeeded".equals(String.valueOf(state))) {
                                    selectedLocalCandidateId = safeString(members.get("localCandidateId"));
                                    selectedRemoteCandidateId = safeString(members.get("remoteCandidateId"));
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
                        maybeLogSelectedRoute(peerId, report, selectedLocalCandidateId, selectedRemoteCandidateId);
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
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer parseSignalGeneration(Map<String, Object> payload) {
        if (payload == null) return null;
        return safeInt(payload.get("gen"));
    }

    private int getPeerGeneration(String peerId) {
        Integer generation = peerGeneration.get(peerId);
        if (generation == null || generation < 1) {
            generation = 1;
            peerGeneration.put(peerId, generation);
        }
        return generation;
    }

    private boolean shouldAcceptCollidingOffer(String peerId) {
        if (localPeerId == null || peerId == null) return true;
        // Deterministic perfect-negotiation role: lexicographically larger id is polite.
        return localPeerId.compareTo(peerId) > 0;
    }

    private void maybeAttemptReconnect(String peerId, String reason) {
        PeerConnection pc = peerConnections.get(peerId);
        if (pc == null) return;
        long now = System.currentTimeMillis();
        long last = peerLastReconnectAttempt.containsKey(peerId) ? peerLastReconnectAttempt.get(peerId) : 0L;
        if (now - last < RECONNECT_DEBOUNCE_MS) return;
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.w(
                TAG,
                "Skip ICE restart for " + peerId + " (" + reason + ") in state " + pc.signalingState()
            );
            return;
        }
        peerLastReconnectAttempt.put(peerId, now);
        callback.onRecoveryState(peerId, "ice_restart", reason);
        Log.i(TAG, "Attempting ICE restart for " + peerId + " after " + reason);
        createOfferInternal(peerId, true);
    }

    private void scheduleHardReset(String peerId, String reason) {
        cancelDisconnectRecoveryTask(peerId);
        ScheduledFuture<?> task = reconnectExecutor.schedule(
            () -> rtcExecutor.execute(() -> maybeHardResetPeer(peerId, reason)),
            HARD_RESET_AFTER_DISCONNECT_MS,
            TimeUnit.MILLISECONDS
        );
        disconnectRecoveryTasks.put(peerId, task);
    }

    private void cancelDisconnectRecoveryTask(String peerId) {
        ScheduledFuture<?> existing = disconnectRecoveryTasks.remove(peerId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void maybeHardResetPeer(String peerId, String reason) {
        disconnectRecoveryTasks.remove(peerId);
        PeerConnection existing = peerConnections.get(peerId);
        if (existing == null) return;
        PeerConnection.IceConnectionState iceState = existing.iceConnectionState();
        if (iceState == PeerConnection.IceConnectionState.CONNECTED
            || iceState == PeerConnection.IceConnectionState.COMPLETED) {
            peerHardResetAttempts.remove(peerId);
            return;
        }

        int attempts = peerHardResetAttempts.containsKey(peerId) ? peerHardResetAttempts.get(peerId) + 1 : 1;
        peerHardResetAttempts.put(peerId, attempts);

        int nextGeneration = getPeerGeneration(peerId) + 1;
        peerGeneration.put(peerId, nextGeneration);
        callback.onRecoveryState(peerId, "hard_reset", reason);
        forceReplacePeerConnection(peerId, "hard-reset-" + reason + "-attempt-" + attempts);
        Log.w(
            TAG,
            "Hard reset peer " + peerId + " after " + reason
                + ", attempts=" + attempts + ", gen=" + nextGeneration
        );
        createOfferInternal(peerId, false);
        if (attempts >= MAX_HARD_RESET_ATTEMPTS) {
            callback.onPeerRecoveryFailure(peerId, attempts, reason);
        }
    }

    private void forceReplacePeerConnection(String peerId, String reason) {
        cancelDisconnectRecoveryTask(peerId);
        PeerConnection previous = peerConnections.remove(peerId);
        if (previous != null) {
            previous.close();
        }
        peerDiagnostics.remove(peerId);
        peerSelectedRouteLogs.remove(peerId);
        peerLastReconnectAttempt.remove(peerId);
        Log.i(TAG, "Recreating peer connection for " + peerId + " due to " + reason);
    }

    private void createOfferInternal(String peerId, boolean iceRestart) {
        PeerConnection pc = ensurePeerConnection(peerId);
        if (pc == null) return;
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "Skip createOffer for " + peerId + " in state " + pc.signalingState());
            return;
        }
        int generation = getPeerGeneration(peerId);
        MediaConstraints constraints = new MediaConstraints();
        if (iceRestart) {
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        }
        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SdpAdapter(), sdp);
                callback.onSignal(
                    peerId,
                    "offer",
                    CollectionsUtil.mapOf(
                        "type", sdp.type.canonicalForm(),
                        "sdp", sdp.description,
                        "gen", generation
                    )
                );
            }
        }, constraints);
    }

    private void updateIceServersIfNeeded() {
        long now = System.currentTimeMillis();
        if (hasTurnServerConfigured() && now < turnCacheExpiryEpochMs) {
            return;
        }
        if (now - turnLastAttemptEpochMs < TURN_RETRY_INTERVAL_MS) {
            return;
        }
        turnLastAttemptEpochMs = now;

        List<PeerConnection.IceServer> turnServers = fetchTurnCredentials();
        resetIceServersToStunOnly();
        if (!turnServers.isEmpty()) {
            iceServers.addAll(turnServers);
            turnCacheExpiryEpochMs = now + Math.max(0L, (TURN_CREDENTIAL_TTL_SECONDS - TURN_CACHE_SAFETY_SECONDS) * 1000L);
            Log.i(
                TAG,
                "Cloudflare TURN credentials loaded, ttl(s)=" + (TURN_CREDENTIAL_TTL_SECONDS - TURN_CACHE_SAFETY_SECONDS)
            );
        } else {
            turnCacheExpiryEpochMs = 0L;
            Log.w(TAG, "Cloudflare TURN unavailable, using STUN only");
        }
    }

    private List<PeerConnection.IceServer> fetchTurnCredentials() {
        HttpURLConnection conn = null;
        try {
            String endpoint = String.format(CLOUDFLARE_TURN_URL, CLOUDFLARE_TURN_KEY_ID);
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + CLOUDFLARE_TURN_API_TOKEN);
            conn.setRequestProperty("Content-Type", "application/json");

            String body = "{\"ttl\":" + TURN_CREDENTIAL_TTL_SECONDS + "}";
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(stream);
            if (status < 200 || status >= 300) {
                Log.w(TAG, "Cloudflare TURN fetch failed: HTTP " + status + " body=" + response);
                return new ArrayList<>();
            }

            JSONObject data = new JSONObject(response);
            Object iceServersObj = data.opt("iceServers");
            if (iceServersObj == null) {
                JSONObject resultObj = data.optJSONObject("result");
                if (resultObj != null) {
                    iceServersObj = resultObj.opt("iceServers");
                }
            }
            List<PeerConnection.IceServer> parsed = parseIceServers(iceServersObj);
            if (parsed.isEmpty()) {
                Log.w(
                    TAG,
                    "Cloudflare TURN fetch returned no usable ICE servers, top-level keys="
                        + data.names()
                );
            }
            return parsed;
        } catch (Throwable t) {
            Log.w(TAG, "Cloudflare TURN fetch exception", t);
            return new ArrayList<>();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private List<PeerConnection.IceServer> parseIceServers(Object iceServersObj) {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        if (iceServersObj instanceof JSONObject) {
            PeerConnection.IceServer server = parseIceServer((JSONObject) iceServersObj);
            if (server != null) out.add(server);
            return out;
        }
        if (iceServersObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) iceServersObj;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                PeerConnection.IceServer server = parseIceServer(item);
                if (server != null) out.add(server);
            }
        }
        return out;
    }

    private PeerConnection.IceServer parseIceServer(JSONObject serverObj) {
        List<String> urls = new ArrayList<>();
        Object urlsObj = serverObj.opt("urls");
        if (urlsObj instanceof String) {
            urls.add((String) urlsObj);
        } else if (urlsObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) urlsObj;
            for (int i = 0; i < arr.length(); i++) {
                String url = arr.optString(i, null);
                if (url != null && !url.isEmpty()) urls.add(url);
            }
        }
        if (urls.isEmpty()) return null;

        PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
        String username = serverObj.optString("username", "");
        String credential = serverObj.optString("credential", "");
        if (!username.isEmpty()) builder.setUsername(username);
        if (!credential.isEmpty()) builder.setPassword(credential);
        return builder.createIceServer();
    }

    private boolean hasTurnServerConfigured() {
        for (PeerConnection.IceServer s : iceServers) {
            if (s == null || s.urls == null) continue;
            for (String url : s.urls) {
                if (url != null && (url.startsWith("turn:") || url.startsWith("turns:"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void resetIceServersToStunOnly() {
        iceServers.clear();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
    }

    private void maybeLogSelectedRoute(
        String peerId,
        RTCStatsReport report,
        String localCandidateId,
        String remoteCandidateId
    ) {
        if (localCandidateId == null && remoteCandidateId == null) return;
        RTCStats local = localCandidateId == null ? null : report.getStatsMap().get(localCandidateId);
        RTCStats remote = remoteCandidateId == null ? null : report.getStatsMap().get(remoteCandidateId);
        String localType = candidateType(local);
        String remoteType = candidateType(remote);
        String route = "local=" + localType + ", remote=" + remoteType;
        String previous = peerSelectedRouteLogs.get(peerId);
        if (route.equals(previous)) return;
        peerSelectedRouteLogs.put(peerId, route);
        boolean relayInUse = "relay".equals(localType) || "relay".equals(remoteType);
        Log.i(
            TAG,
            "Peer " + peerId + " selected route: " + route + (relayInUse ? " (TURN relay)" : " (direct)")
        );
    }

    private String candidateType(RTCStats stat) {
        if (stat == null) return "unknown";
        Map<String, Object> members = stat.getMembers();
        if (members == null) return "unknown";
        String candidateType = safeString(members.get("candidateType"));
        if (candidateType == null) candidateType = safeString(members.get("type"));
        if (candidateType == null || candidateType.isEmpty()) return "unknown";
        return candidateType;
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        }
        return out.toString();
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
