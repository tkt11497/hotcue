package com.gcn.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(
    name = "NativeWebRTC",
    permissions = {
        @Permission(
            strings = { Manifest.permission.RECORD_AUDIO },
            alias = "microphone"
        )
    }
)
public class NativeWebRTCPlugin extends Plugin {

    private static final String TAG = "NativeWebRTC";

    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private boolean isMuted = false;
    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_NORMAL;
    private boolean savedSpeakerphone = false;

    private final Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private final Map<String, List<IceCandidate>> pendingCandidates = new ConcurrentHashMap<>();
    // Tracks which peers have had their remote description set successfully
    private final Set<String> remoteDescriptionSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private List<PeerConnection.IceServer> iceServers = new ArrayList<>();

    // --- Native heartbeat fields ---
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private volatile String hbRoomId;
    private volatile String hbUserId;
    private volatile String hbIdToken;
    private volatile String hbProjectId;
    private volatile boolean heartbeatRunning = false;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!heartbeatRunning) return;
            sendHeartbeatHttp();
            if (heartbeatHandler != null) {
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    @Override
    public void load() {
        try {
            audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getContext().getApplicationContext())
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            );

            factory = PeerConnectionFactory.builder()
                .createPeerConnectionFactory();

            Log.d(TAG, "PeerConnectionFactory initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize PeerConnectionFactory", e);
        }
    }

    private void configureAudioForCall() {
        if (audioManager == null) return;
        try {
            savedAudioMode = audioManager.getMode();
            savedSpeakerphone = audioManager.isSpeakerphoneOn();

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(
                    new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                );
            }

            Log.d(TAG, "Audio configured: MODE_IN_COMMUNICATION, speaker ON");
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure audio", e);
        }
    }

    private void restoreAudio() {
        if (audioManager == null) return;
        try {
            audioManager.setMode(savedAudioMode);
            audioManager.setSpeakerphoneOn(savedSpeakerphone);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(
                    new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build()
                );
            }
            Log.d(TAG, "Audio restored to previous state");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore audio", e);
        }
    }

    @PluginMethod()
    public void updateIceServers(PluginCall call) {
        try {
            JSArray servers = call.getArray("servers");
            iceServers.clear();

            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.getJSONObject(i);
                JSONArray urlsArray = server.optJSONArray("urls");
                String urlsSingle = server.optString("urls", "");

                PeerConnection.IceServer.Builder builder;
                if (urlsArray != null) {
                    List<String> urlList = new ArrayList<>();
                    for (int j = 0; j < urlsArray.length(); j++) {
                        urlList.add(urlsArray.getString(j));
                    }
                    builder = PeerConnection.IceServer.builder(urlList);
                } else {
                    builder = PeerConnection.IceServer.builder(urlsSingle);
                }

                String username = server.optString("username", "");
                String credential = server.optString("credential", "");
                if (!username.isEmpty()) builder.setUsername(username);
                if (!credential.isEmpty()) builder.setPassword(credential);

                iceServers.add(builder.createIceServer());
            }

            Log.d(TAG, "ICE servers updated: " + iceServers.size());
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to parse ICE servers", e);
        }
    }

    @PluginMethod()
    public void startMicrophone(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "onMicrophonePermissionResult");
            return;
        }
        doStartMicrophone(call);
    }

    @PermissionCallback
    private void onMicrophonePermissionResult(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            doStartMicrophone(call);
        } else {
            call.reject("Microphone permission denied");
        }
    }

    private void doStartMicrophone(PluginCall call) {
        try {
            configureAudioForCall();

            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("echoCancellation", "true"));
            audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("noiseSuppression", "true"));
            audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("autoGainControl", "true"));

            audioSource = factory.createAudioSource(audioConstraints);
            localAudioTrack = factory.createAudioTrack("audio0", audioSource);
            localAudioTrack.setEnabled(true);
            isMuted = false;

            Log.d(TAG, "Microphone started (native), speaker ON");
            call.resolve();
        } catch (Exception e) {
            restoreAudio();
            call.reject("Failed to start microphone: " + e.getMessage(), e);
        }
    }

    @PluginMethod()
    public void stopMicrophone(PluginCall call) {
        try {
            if (localAudioTrack != null) {
                localAudioTrack.setEnabled(false);
                localAudioTrack.dispose();
                localAudioTrack = null;
            }
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
            restoreAudio();
        } catch (Exception e) {
            Log.w(TAG, "Error stopping microphone", e);
        }
        call.resolve();
    }

    @PluginMethod()
    public void setMuted(PluginCall call) {
        isMuted = call.getBoolean("muted", false);
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!isMuted);
        }
        call.resolve();
    }

    @PluginMethod()
    public void setSpeakerphone(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", true);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(enabled);
            Log.d(TAG, "Speakerphone: " + enabled);
        }
        call.resolve();
    }

    @PluginMethod()
    public void createOffer(PluginCall call) {
        String peerId = call.getString("peerId");
        if (peerId == null) {
            call.reject("peerId required");
            return;
        }

        try {
            PeerConnection pc = createPeerConnectionInternal(peerId);
            if (pc == null) {
                call.reject("Failed to create PeerConnection");
                return;
            }

            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

            pc.createOffer(new SdpAdapter("createOffer") {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    try {
                        pc.setLocalDescription(new SdpAdapter("setLocalOffer") {
                            @Override
                            public void onSetSuccess() {
                                try {
                                    JSObject result = new JSObject();
                                    result.put("type", sdp.type.canonicalForm());
                                    result.put("sdp", sdp.description);
                                    call.resolve(result);
                                } catch (Exception e) {
                                    Log.e(TAG, "createOffer resolve error", e);
                                    call.reject("Internal error", e);
                                }
                            }

                            @Override
                            public void onSetFailure(String error) {
                                call.reject("setLocalDescription failed: " + error);
                            }
                        }, sdp);
                    } catch (Exception e) {
                        Log.e(TAG, "setLocalDescription error", e);
                        call.reject("Internal error", e);
                    }
                }

                @Override
                public void onCreateFailure(String error) {
                    call.reject("createOffer failed: " + error);
                }
            }, constraints);
        } catch (Exception e) {
            Log.e(TAG, "createOffer exception", e);
            call.reject("createOffer exception: " + e.getMessage(), e);
        }
    }

    @PluginMethod()
    public void handleOffer(PluginCall call) {
        String peerId = call.getString("peerId");
        String sdp = call.getString("sdp");
        String type = call.getString("type", "offer");

        if (peerId == null || sdp == null) {
            call.reject("peerId and sdp required");
            return;
        }

        try {
            PeerConnection pc = createPeerConnectionInternal(peerId);
            if (pc == null) {
                call.reject("Failed to create PeerConnection");
                return;
            }

            SessionDescription remoteSdp = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type), sdp
            );

            pc.setRemoteDescription(new SdpAdapter("setRemoteOffer") {
                @Override
                public void onSetSuccess() {
                    try {
                        remoteDescriptionSet.add(peerId);
                        drainPendingCandidates(peerId);

                        MediaConstraints constraints = new MediaConstraints();
                        constraints.mandatory.add(
                            new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

                        pc.createAnswer(new SdpAdapter("createAnswer") {
                            @Override
                            public void onCreateSuccess(SessionDescription answerSdp) {
                                try {
                                    pc.setLocalDescription(new SdpAdapter("setLocalAnswer") {
                                        @Override
                                        public void onSetSuccess() {
                                            try {
                                                JSObject result = new JSObject();
                                                result.put("type", answerSdp.type.canonicalForm());
                                                result.put("sdp", answerSdp.description);
                                                call.resolve(result);
                                            } catch (Exception e) {
                                                Log.e(TAG, "handleOffer resolve error", e);
                                                call.reject("Internal error", e);
                                            }
                                        }

                                        @Override
                                        public void onSetFailure(String error) {
                                            call.reject("setLocalDescription failed: " + error);
                                        }
                                    }, answerSdp);
                                } catch (Exception e) {
                                    Log.e(TAG, "setLocalDescription answer error", e);
                                    call.reject("Internal error", e);
                                }
                            }

                            @Override
                            public void onCreateFailure(String error) {
                                call.reject("createAnswer failed: " + error);
                            }
                        }, constraints);
                    } catch (Exception e) {
                        Log.e(TAG, "handleOffer onSetSuccess error", e);
                        call.reject("Internal error", e);
                    }
                }

                @Override
                public void onSetFailure(String error) {
                    call.reject("setRemoteDescription failed: " + error);
                }
            }, remoteSdp);
        } catch (Exception e) {
            Log.e(TAG, "handleOffer exception", e);
            call.reject("handleOffer exception: " + e.getMessage(), e);
        }
    }

    @PluginMethod()
    public void handleAnswer(PluginCall call) {
        String peerId = call.getString("peerId");
        String sdp = call.getString("sdp");
        String type = call.getString("type", "answer");

        if (peerId == null || sdp == null) {
            call.reject("peerId and sdp required");
            return;
        }

        PeerConnection pc = peerConnections.get(peerId);
        if (pc == null) {
            call.reject("No PeerConnection for " + peerId);
            return;
        }

        try {
            SessionDescription remoteSdp = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type), sdp
            );

            pc.setRemoteDescription(new SdpAdapter("setRemoteAnswer") {
                @Override
                public void onSetSuccess() {
                    try {
                        remoteDescriptionSet.add(peerId);
                        drainPendingCandidates(peerId);
                    } catch (Exception e) {
                        Log.e(TAG, "drainPendingCandidates error", e);
                    }
                    call.resolve();
                }

                @Override
                public void onSetFailure(String error) {
                    call.reject("setRemoteDescription failed: " + error);
                }
            }, remoteSdp);
        } catch (Exception e) {
            Log.e(TAG, "handleAnswer exception", e);
            call.reject("handleAnswer exception: " + e.getMessage(), e);
        }
    }

    /**
     * ALWAYS queues the candidate. Candidates are only applied to the
     * PeerConnection from within SDP success callbacks (drainPendingCandidates),
     * which execute on WebRTC's signaling thread — the only safe context
     * for calling pc.addIceCandidate().
     */
    @PluginMethod()
    public void addIceCandidate(PluginCall call) {
        String peerId = call.getString("peerId");
        String candidateStr = call.getString("candidate");
        String sdpMid = call.getString("sdpMid", "");
        int sdpMLineIndex = call.getInt("sdpMLineIndex", 0);

        if (peerId == null || candidateStr == null) {
            call.reject("peerId and candidate required");
            return;
        }

        try {
            IceCandidate candidate = new IceCandidate(
                sdpMid != null ? sdpMid : "", sdpMLineIndex, candidateStr
            );

            List<IceCandidate> queue = pendingCandidates.get(peerId);
            if (queue == null) {
                queue = Collections.synchronizedList(new ArrayList<>());
                pendingCandidates.put(peerId, queue);
            }
            queue.add(candidate);

            // If remote description already set, drain on main thread
            // which will proxy to the PC's internal thread safely
            if (remoteDescriptionSet.contains(peerId)) {
                PeerConnection pc = peerConnections.get(peerId);
                if (pc != null) {
                    drainPendingCandidates(peerId);
                }
            } else {
                Log.d(TAG, "Queued ICE candidate for " + peerId + " (remote desc not set yet)");
            }
        } catch (Exception e) {
            Log.w(TAG, "addIceCandidate error for " + peerId, e);
        }
        call.resolve();
    }

    // --- Native heartbeat methods ---

    @PluginMethod()
    public void startHeartbeat(PluginCall call) {
        hbRoomId = call.getString("roomId");
        hbUserId = call.getString("userId");
        hbIdToken = call.getString("idToken");
        hbProjectId = call.getString("projectId", "hot-cue");

        if (hbRoomId == null || hbUserId == null || hbIdToken == null) {
            call.reject("roomId, userId, and idToken required");
            return;
        }

        stopHeartbeatInternal();

        heartbeatThread = new HandlerThread("NativeWebRTC-Heartbeat");
        heartbeatThread.start();
        heartbeatHandler = new Handler(heartbeatThread.getLooper());
        heartbeatRunning = true;
        heartbeatHandler.post(heartbeatRunnable);

        Log.d(TAG, "Native heartbeat started for room=" + hbRoomId + " user=" + hbUserId);
        call.resolve();
    }

    @PluginMethod()
    public void stopHeartbeat(PluginCall call) {
        stopHeartbeatInternal();
        Log.d(TAG, "Native heartbeat stopped");
        call.resolve();
    }

    @PluginMethod()
    public void updateHeartbeatToken(PluginCall call) {
        String newToken = call.getString("idToken");
        if (newToken == null) {
            call.reject("idToken required");
            return;
        }
        hbIdToken = newToken;
        Log.d(TAG, "Heartbeat token refreshed");
        call.resolve();
    }

    private void stopHeartbeatInternal() {
        heartbeatRunning = false;
        if (heartbeatHandler != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatHandler = null;
        }
        if (heartbeatThread != null) {
            heartbeatThread.quitSafely();
            heartbeatThread = null;
        }
    }

    private void sendHeartbeatHttp() {
        String roomId = hbRoomId;
        String userId = hbUserId;
        String token = hbIdToken;
        String projectId = hbProjectId;
        if (roomId == null || userId == null || token == null || projectId == null) return;

        HttpURLConnection conn = null;
        try {
            String urlStr = "https://firestore.googleapis.com/v1/projects/" + projectId
                + "/databases/(default)/documents/rooms/" + roomId
                + "/users/" + userId
                + "?updateMask.fieldPaths=lastSeen";

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String nowIso = sdf.format(new Date());

            String body = "{\"fields\":{\"lastSeen\":{\"timestampValue\":\"" + nowIso + "\"}}}";

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("PATCH");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                Log.d(TAG, "Heartbeat sent OK (" + code + ")");
            } else {
                Log.w(TAG, "Heartbeat HTTP " + code);
            }
        } catch (Exception e) {
            Log.w(TAG, "Heartbeat request failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @PluginMethod()
    public void requestBatteryExemption(PluginCall call) {
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            call.resolve();
        } catch (Exception e) {
            Log.w(TAG, "requestBatteryExemption error", e);
            call.reject("Failed to request battery exemption", e);
        }
    }

    @PluginMethod()
    public void removePeer(PluginCall call) {
        String peerId = call.getString("peerId");
        if (peerId == null) {
            call.reject("peerId required");
            return;
        }

        try {
            remoteDescriptionSet.remove(peerId);
            PeerConnection pc = peerConnections.remove(peerId);
            if (pc != null) pc.close();
            pendingCandidates.remove(peerId);
            Log.d(TAG, "Removed peer: " + peerId);
        } catch (Exception e) {
            Log.w(TAG, "removePeer error", e);
        }
        call.resolve();
    }

    @PluginMethod()
    public void closeAllPeers(PluginCall call) {
        try {
            for (PeerConnection pc : peerConnections.values()) {
                try {
                    pc.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing PC", e);
                }
            }
            peerConnections.clear();
            pendingCandidates.clear();
            remoteDescriptionSet.clear();
            Log.d(TAG, "All peers closed");
        } catch (Exception e) {
            Log.w(TAG, "closeAllPeers error", e);
        }
        call.resolve();
    }

    @PluginMethod()
    public void getStats(PluginCall call) {
        PeerConnection target = null;
        for (PeerConnection p : peerConnections.values()) {
            PeerConnection.IceConnectionState s = p.iceConnectionState();
            if (s == PeerConnection.IceConnectionState.CONNECTED ||
                s == PeerConnection.IceConnectionState.COMPLETED) {
                target = p;
                break;
            }
        }

        if (target == null) {
            JSObject result = new JSObject();
            result.put("rtt", JSObject.NULL);
            result.put("jitter", JSObject.NULL);
            result.put("packetsLost", 0);
            result.put("packetsSent", 0);
            result.put("packetsReceived", 0);
            call.resolve(result);
            return;
        }

        target.getStats(report -> {
            try {
                double rtt = -1;
                double jitter = -1;
                long packetsLost = 0;
                long packetsSent = 0;
                long packetsReceived = 0;

                for (org.webrtc.RTCStats stats : report.getStatsMap().values()) {
                    String type = stats.getType();
                    Map<String, Object> members = stats.getMembers();

                    if ("remote-inbound-rtp".equals(type)) {
                        Object rttObj = members.get("roundTripTime");
                        if (rttObj instanceof Number) {
                            rtt = ((Number) rttObj).doubleValue();
                        }
                    } else if ("inbound-rtp".equals(type)) {
                        Object jObj = members.get("jitter");
                        if (jObj instanceof Number) {
                            jitter = ((Number) jObj).doubleValue();
                        }
                        Object lObj = members.get("packetsLost");
                        if (lObj instanceof Number) {
                            packetsLost += ((Number) lObj).longValue();
                        }
                        Object rObj = members.get("packetsReceived");
                        if (rObj instanceof Number) {
                            packetsReceived += ((Number) rObj).longValue();
                        }
                    } else if ("outbound-rtp".equals(type)) {
                        Object sObj = members.get("packetsSent");
                        if (sObj instanceof Number) {
                            packetsSent += ((Number) sObj).longValue();
                        }
                    }
                }

                JSObject result = new JSObject();
                result.put("rtt", rtt >= 0 ? Math.round(rtt * 1000) : JSObject.NULL);
                result.put("jitter", jitter >= 0 ? Math.round(jitter * 1000) : JSObject.NULL);
                result.put("packetsLost", packetsLost);
                result.put("packetsSent", packetsSent);
                result.put("packetsReceived", packetsReceived);
                call.resolve(result);
            } catch (Exception e) {
                Log.w(TAG, "getStats processing error", e);
                JSObject result = new JSObject();
                result.put("rtt", JSObject.NULL);
                result.put("jitter", JSObject.NULL);
                result.put("packetsLost", 0);
                result.put("packetsSent", 0);
                result.put("packetsReceived", 0);
                call.resolve(result);
            }
        });
    }

    @PluginMethod()
    public void getPeerStates(PluginCall call) {
        JSObject result = new JSObject();
        try {
            for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                PeerConnection pc = entry.getValue();
                JSObject state = new JSObject();
                PeerConnection.IceConnectionState iceState = pc.iceConnectionState();
                state.put("connectionState", mapIceState(iceState));
                state.put("iceState", iceState != null ? iceState.name().toLowerCase() : "unknown");
                result.put(entry.getKey(), state);
            }
        } catch (Exception e) {
            Log.w(TAG, "getPeerStates error", e);
        }
        call.resolve(result);
    }

    // --- Internal helpers ---

    private PeerConnection createPeerConnectionInternal(String peerId) {
        PeerConnection existing = peerConnections.get(peerId);
        if (existing != null) {
            try {
                existing.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing existing PC for " + peerId, e);
            }
            peerConnections.remove(peerId);
        }
        remoteDescriptionSet.remove(peerId);
        pendingCandidates.put(peerId, Collections.synchronizedList(new ArrayList<>()));

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        PeerConnection pc = factory.createPeerConnection(config, new SafePeerConnectionObserver(peerId));

        if (pc == null) {
            Log.e(TAG, "Failed to create PeerConnection for " + peerId);
            return null;
        }

        if (localAudioTrack != null) {
            try {
                List<String> streamIds = new ArrayList<>();
                streamIds.add("local-stream");
                pc.addTrack(localAudioTrack, streamIds);
                Log.d(TAG, "Added local audio track for " + peerId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add local audio track for " + peerId, e);
            }
        } else {
            Log.w(TAG, "No local audio track when creating PC for " + peerId);
        }

        peerConnections.put(peerId, pc);
        return pc;
    }

    /**
     * Drains queued ICE candidates into the PeerConnection.
     * Safe to call from any thread — addIceCandidate internally
     * posts to the network thread.
     */
    private void drainPendingCandidates(String peerId) {
        PeerConnection pc = peerConnections.get(peerId);
        List<IceCandidate> queued = pendingCandidates.get(peerId);
        if (pc == null || queued == null || queued.isEmpty()) return;

        List<IceCandidate> snapshot;
        synchronized (queued) {
            snapshot = new ArrayList<>(queued);
            queued.clear();
        }

        Log.d(TAG, "Draining " + snapshot.size() + " candidates for " + peerId);
        for (IceCandidate candidate : snapshot) {
            try {
                pc.addIceCandidate(candidate);
            } catch (Exception e) {
                Log.w(TAG, "Failed to add queued candidate for " + peerId, e);
            }
        }
    }

    private String mapIceState(PeerConnection.IceConnectionState state) {
        if (state == null) return "unknown";
        switch (state) {
            case NEW: return "new";
            case CHECKING: return "connecting";
            case CONNECTED: return "connected";
            case COMPLETED: return "connected";
            case DISCONNECTED: return "disconnected";
            case FAILED: return "failed";
            case CLOSED: return "closed";
            default: return "unknown";
        }
    }

    @Override
    protected void handleOnDestroy() {
        try {
            stopHeartbeatInternal();

            for (PeerConnection pc : peerConnections.values()) {
                try { pc.close(); } catch (Exception ignored) {}
            }
            peerConnections.clear();
            pendingCandidates.clear();
            remoteDescriptionSet.clear();

            if (localAudioTrack != null) {
                try { localAudioTrack.dispose(); } catch (Exception ignored) {}
                localAudioTrack = null;
            }
            if (audioSource != null) {
                try { audioSource.dispose(); } catch (Exception ignored) {}
                audioSource = null;
            }
            if (factory != null) {
                try { factory.dispose(); } catch (Exception ignored) {}
                factory = null;
            }
            restoreAudio();
        } catch (Exception e) {
            Log.w(TAG, "handleOnDestroy error", e);
        }
    }

    /**
     * All callbacks wrapped in try-catch. WebRTC calls these on its internal
     * signaling/network threads. Any uncaught exception causes SIGABRT via
     * "Check failed: !env->ExceptionCheck()".
     */
    private class SafePeerConnectionObserver implements PeerConnection.Observer {
        private final String peerId;

        SafePeerConnectionObserver(String peerId) {
            this.peerId = peerId;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
            try {
                Log.d(TAG, peerId + " signaling: " + state);
            } catch (Exception ignored) {}
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            try {
                Log.d(TAG, peerId + " ice: " + state);
                String mapped = mapIceState(state);

                JSObject data = new JSObject();
                data.put("peerId", peerId);
                data.put("state", mapped);
                notifyListeners("onConnectionStateChange", data);

                if (state == PeerConnection.IceConnectionState.FAILED) {
                    JSObject unreachable = new JSObject();
                    unreachable.put("peerId", peerId);
                    notifyListeners("onPeerUnreachable", unreachable);
                }
            } catch (Exception e) {
                Log.e(TAG, peerId + " onIceConnectionChange error", e);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {}

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            try {
                Log.d(TAG, peerId + " iceGathering: " + state);
            } catch (Exception ignored) {}
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            try {
                Log.d(TAG, peerId + " ICE candidate generated");
                JSObject data = new JSObject();
                data.put("peerId", peerId);
                JSObject cand = new JSObject();
                cand.put("candidate", candidate.sdp != null ? candidate.sdp : "");
                cand.put("sdpMid", candidate.sdpMid != null ? candidate.sdpMid : "");
                cand.put("sdpMLineIndex", candidate.sdpMLineIndex);
                data.put("candidate", cand);
                notifyListeners("onIceCandidate", data);
            } catch (Exception e) {
                Log.e(TAG, peerId + " onIceCandidate error", e);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}

        @Override
        public void onAddStream(MediaStream stream) {}

        @Override
        public void onRemoveStream(MediaStream stream) {}

        @Override
        public void onDataChannel(DataChannel channel) {}

        @Override
        public void onRenegotiationNeeded() {}

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            try {
                String kind = "unknown";
                if (receiver != null && receiver.track() != null) {
                    kind = receiver.track().kind();
                }
                Log.d(TAG, peerId + " remote track added: " + kind);
            } catch (Exception e) {
                Log.e(TAG, peerId + " onAddTrack error", e);
            }
        }
    }

    private static abstract class SdpAdapter implements SdpObserver {
        private final String label;

        SdpAdapter(String label) {
            this.label = label;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String error) {
            Log.e(TAG, label + " create failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.e(TAG, label + " set failed: " + error);
        }
    }
}
