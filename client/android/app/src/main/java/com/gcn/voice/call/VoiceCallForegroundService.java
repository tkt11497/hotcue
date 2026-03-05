package com.gcn.voice.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.gcn.voice.MainActivity;
import com.gcn.voice.R;
import com.gcn.voice.VoiceWidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VoiceCallForegroundService extends Service {

    public static final String ACTION_START_CALL = "com.gcn.voice.call.START_CALL";
    public static final String ACTION_HANGUP = "com.gcn.voice.call.HANGUP";
    public static final String ACTION_TOGGLE_MUTE = "com.gcn.voice.call.TOGGLE_MUTE";
    public static final String ACTION_CALL_STATE_UPDATED = "com.gcn.voice.call.STATE_UPDATED";

    public static final String EXTRA_ROOM_ID = "roomId";
    public static final String EXTRA_ROOM_NAME = "roomName";
    public static final String EXTRA_USER_ID = "userId";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_FIREBASE_API_KEY = "firebaseApiKey";
    public static final String EXTRA_FIREBASE_APP_ID = "firebaseAppId";
    public static final String EXTRA_FIREBASE_PROJECT_ID = "firebaseProjectId";
    public static final String EXTRA_FIREBASE_STORAGE_BUCKET = "firebaseStorageBucket";
    public static final String EXTRA_FIREBASE_MESSAGING_SENDER_ID = "firebaseMessagingSenderId";

    private static final String CHANNEL_ID = "gcn_voice_call";
    private static final String ALERT_CHANNEL_ID = "gcn_voice_call_alert";
    private static final int NOTIFICATION_ID = 4101;
    private static final int ALERT_NOTIFICATION_ID = 4102;
    private static final long DISCONNECTED_ALERT_DELAY_MS = 8_000L;

    private final CallStateStore stateStore = new CallStateStore();
    private NativeSignalingClient signalingClient;
    private NativeWebRtcManager webRtcManager;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foregroundStarted = false;
    private String currentRoomName = null;
    private boolean connectionIssueAlertSent = false;
    private String callPhase = "idle";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> disconnectAlertTasks = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildBootstrapNotification());
        foregroundStarted = true;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GCNVoice::NativeCallCPU");

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GCNVoice::NativeCallWifi");
        }

        webRtcManager = new NativeWebRtcManager(new NativeWebRtcManager.Callback() {
            @Override
            public void onSignal(String to, String type, Map<String, Object> payload) {
                if (signalingClient != null) signalingClient.sendSignal(to, type, payload);
            }

            @Override
            public void onPeerState(String peerId, NativeWebRtcManager.PeerDiagnostics diagnostics) {
                stateStore.upsertPeer(
                    new CallStateStore.PeerSnapshot(
                        peerId,
                        diagnostics.connectionState,
                        diagnostics.iceState,
                        diagnostics.rttMs,
                        diagnostics.jitterMs,
                        diagnostics.packetsLost,
                        diagnostics.remoteTrackCount
                    )
                );
                updateCallPhaseFromPeerState(diagnostics.connectionState);
                publishState();
                handleDelayedDisconnectAlert(peerId, diagnostics.connectionState);
                if ("failed".equals(diagnostics.connectionState) || "closed".equals(diagnostics.connectionState)) {
                    notifyConnectionIssue("Connection issue detected in room " + (currentRoomName == null ? "voice call" : currentRoomName));
                }
            }

            @Override
            public void onError(String message) {
                Log.e("VoiceCallService", message);
            }
        });
        webRtcManager.initialize(getApplicationContext());

        // Initialized lazily in ACTION_START_CALL where Firebase options may be provided by JS.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START_CALL.equals(action)) {
            callPhase = "joining_room";
            String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
            String roomName = intent.getStringExtra(EXTRA_ROOM_NAME);
            String userId = intent.getStringExtra(EXTRA_USER_ID);
            String username = intent.getStringExtra(EXTRA_USERNAME);
            String firebaseApiKey = intent.getStringExtra(EXTRA_FIREBASE_API_KEY);
            String firebaseAppId = intent.getStringExtra(EXTRA_FIREBASE_APP_ID);
            String firebaseProjectId = intent.getStringExtra(EXTRA_FIREBASE_PROJECT_ID);
            String firebaseStorageBucket = intent.getStringExtra(EXTRA_FIREBASE_STORAGE_BUCKET);
            String firebaseMessagingSenderId = intent.getStringExtra(EXTRA_FIREBASE_MESSAGING_SENDER_ID);

            if (signalingClient == null) {
                initSignalingClient(
                    firebaseApiKey,
                    firebaseAppId,
                    firebaseProjectId,
                    firebaseStorageBucket,
                    firebaseMessagingSenderId
                );
            }

            if (roomId != null && userId != null && username != null) {
                startCall(roomId, roomName, userId, username);
            }
        } else if (ACTION_TOGGLE_MUTE.equals(action)) {
            toggleMute();
        } else if (ACTION_HANGUP.equals(action)) {
            hangup();
        }
        return START_NOT_STICKY;
    }

    private void startCall(String roomId, String roomName, String userId, String username) {
        if (signalingClient == null) {
            Log.e("VoiceCallService", "Cannot start call: signaling client not initialized");
            callPhase = "failed";
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foregroundStarted = false;
            }
            publishState();
            stopSelf();
            return;
        }
        currentRoomName = (roomName == null || roomName.isEmpty()) ? roomId : roomName;
        connectionIssueAlertSent = false;
        callPhase = "signaling_ready";
        clearDisconnectAlertTasks();
        stateStore.beginCall(roomId, userId, username);
        acquireLocks();
        updateForegroundNotification();
        callPhase = "rtc_connecting";
        signalingClient.joinRoom(roomId, userId, username);
        publishState();
    }

    private void initSignalingClient(
        String firebaseApiKey,
        String firebaseAppId,
        String firebaseProjectId,
        String firebaseStorageBucket,
        String firebaseMessagingSenderId
    ) {
        try {
            signalingClient = new NativeSignalingClient(
                getApplicationContext(),
                new NativeSignalingClient.Callback() {
                    @Override
                    public void onPeerJoined(String peerId) {
                        webRtcManager.createOffer(peerId);
                    }

                    @Override
                    public void onPeerLeft(String peerId) {
                        webRtcManager.removePeer(peerId);
                        stateStore.removePeer(peerId);
                        publishState();
                    }

                    @Override
                    public void onSignal(String from, String type, Map<String, Object> payload) {
                        if ("offer".equals(type)) webRtcManager.handleOffer(from, payload);
                        else if ("answer".equals(type)) webRtcManager.handleAnswer(from, payload);
                        else if ("ice-candidate".equals(type)) webRtcManager.handleIceCandidate(from, payload);
                    }

                    @Override
                    public void onUsers(List<CallStateStore.UserSnapshot> users) {
                        stateStore.setUsers(users);
                        if ("joining_room".equals(callPhase)) {
                            callPhase = "signaling_ready";
                        }
                        publishState();
                    }

                    @Override
                    public void onError(String message) {
                        Log.e("VoiceCallService", message);
                    }
                },
                firebaseApiKey,
                firebaseAppId,
                firebaseProjectId,
                firebaseStorageBucket,
                firebaseMessagingSenderId
            );
        } catch (Exception e) {
            Log.e("VoiceCallService", "Failed to initialize native signaling", e);
            signalingClient = null;
        }
    }

    private void toggleMute() {
        boolean nextMuted = !stateStore.isMuted();
        stateStore.setMuted(nextMuted);
        webRtcManager.setMuted(nextMuted);
        if (signalingClient != null) signalingClient.setMuted(nextMuted);
        updateForegroundNotification();
        publishState();
    }

    private void hangup() {
        clearDisconnectAlertTasks();
        callPhase = "idle";
        stateStore.endCall();
        if (signalingClient != null) signalingClient.leaveRoom();
        if (webRtcManager != null) webRtcManager.closeAll();
        releaseLocks();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        publishState();
        stopSelf();
    }

    private Notification buildBootstrapNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            100,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GCN Voice")
            .setContentText("Starting call service...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            100,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent muteIntent = new Intent(this, VoiceCallForegroundService.class);
        muteIntent.setAction(ACTION_TOGGLE_MUTE);
        PendingIntent mutePending = PendingIntent.getService(
            this,
            101,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent hangupIntent = new Intent(this, VoiceCallForegroundService.class);
        hangupIntent.setAction(ACTION_HANGUP);
        PendingIntent hangupPending = PendingIntent.getService(
            this,
            102,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        CallStateStore.Snapshot snap = stateStore.snapshot();
        String roomLabel = currentRoomName == null ? "Voice call active" : "Room: " + currentRoomName;
        String muteLabel = snap.isMuted ? "Unmute" : "Mute";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GCN Voice")
            .setContentText(roomLabel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, muteLabel, mutePending)
            .addAction(0, "Hang up", hangupPending)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Voice Calls",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Persistent notification for active voice calls");

        NotificationChannel alertChannel = new NotificationChannel(
            ALERT_CHANNEL_ID,
            "Voice Alerts",
            NotificationManager.IMPORTANCE_HIGH
        );
        alertChannel.setDescription("Connection loss and call-end alerts");
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        alertChannel.setSound(soundUri, attrs);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
            nm.createNotificationChannel(alertChannel);
        }
    }

    private void updateForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void notifyConnectionIssue(String message) {
        if (connectionIssueAlertSent) return;
        connectionIssueAlertSent = true;
        clearDisconnectAlertTasks();

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            200,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification alert = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GCN Voice")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentIntent)
            .setDefaults(Notification.DEFAULT_SOUND)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(ALERT_NOTIFICATION_ID, alert);
        }
    }

    private void handleDelayedDisconnectAlert(String peerId, String connectionState) {
        Runnable existing = disconnectAlertTasks.remove(peerId);
        if (existing != null) {
            mainHandler.removeCallbacks(existing);
        }

        if ("disconnected".equals(connectionState)) {
            Runnable task = () -> notifyConnectionIssue(
                "Connection lost in room " + (currentRoomName == null ? "voice call" : currentRoomName)
            );
            disconnectAlertTasks.put(peerId, task);
            mainHandler.postDelayed(task, DISCONNECTED_ALERT_DELAY_MS);
        }
    }

    private void updateCallPhaseFromPeerState(String connectionState) {
        if ("failed".equals(connectionState) || "closed".equals(connectionState)) {
            callPhase = "failed";
            return;
        }
        if ("connected".equals(connectionState)) {
            callPhase = "connected";
            return;
        }
        if ("disconnected".equals(connectionState)) {
            if ("connected".equals(callPhase) || "degraded".equals(callPhase)) {
                callPhase = "degraded";
            } else {
                callPhase = "rtc_connecting";
            }
            return;
        }
        if ("rtc_connecting".equals(callPhase) || "signaling_ready".equals(callPhase) || "joining_room".equals(callPhase)) {
            callPhase = "rtc_connecting";
        }
    }

    private void clearDisconnectAlertTasks() {
        for (Runnable task : disconnectAlertTasks.values()) {
            mainHandler.removeCallbacks(task);
        }
        disconnectAlertTasks.clear();
    }

    private void acquireLocks() {
        try {
            if (cpuWakeLock != null && !cpuWakeLock.isHeld()) cpuWakeLock.acquire(4 * 60 * 60 * 1000L);
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable t) {
            Log.w("VoiceCallService", "Failed to acquire locks", t);
        }
    }

    private void releaseLocks() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable t) {
            Log.w("VoiceCallService", "Failed to release locks", t);
        }
    }

    private void publishState() {
        CallStateStore.Snapshot snap = stateStore.snapshot();
        try {
            JSONObject root = new JSONObject();
            root.put("inCall", snap.inCall);
            root.put("connected", snap.connected);
            root.put("isMuted", snap.isMuted);
            root.put("roomId", snap.roomId == null ? JSONObject.NULL : snap.roomId);
            root.put("roomName", currentRoomName == null ? JSONObject.NULL : currentRoomName);
            root.put("myId", snap.myId == null ? JSONObject.NULL : snap.myId);
            root.put("myUsername", snap.myUsername == null ? JSONObject.NULL : snap.myUsername);
            root.put("startedAtEpochMs", snap.startedAtEpochMs);
            root.put("callPhase", callPhase);

            JSONArray users = new JSONArray();
            for (CallStateStore.UserSnapshot u : snap.users) {
                JSONObject item = new JSONObject();
                item.put("id", u.id);
                item.put("username", u.username);
                item.put("isMuted", u.isMuted);
                users.put(item);
            }
            root.put("users", users);

            JSONArray peers = new JSONArray();
            for (CallStateStore.PeerSnapshot p : snap.peers) {
                JSONObject item = new JSONObject();
                item.put("peerId", p.peerId);
                item.put("connectionState", p.connectionState);
                item.put("iceState", p.iceState);
                item.put("rttMs", p.rttMs);
                item.put("jitterMs", p.jitterMs);
                item.put("packetsLost", p.packetsLost);
                item.put("remoteTrackCount", p.remoteTrackCount);
                peers.put(item);
            }
            root.put("peers", peers);

            Intent stateIntent = new Intent(ACTION_CALL_STATE_UPDATED);
            stateIntent.putExtra("state", root.toString());
            stateIntent.setPackage(getPackageName());
            sendBroadcast(stateIntent);

            if (snap.inCall && snap.roomId != null) {
                updateWidgetState(snap);
            } else {
                clearWidgetState();
            }
        } catch (Throwable t) {
            Log.e("VoiceCallService", "Failed to publish state", t);
        }
    }

    private void updateWidgetState(CallStateStore.Snapshot snap) {
        getSharedPreferences(VoiceWidget.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean("inCall", true)
            .putString("roomName", currentRoomName == null ? (snap.roomId == null ? "" : snap.roomId) : currentRoomName)
            .putBoolean("isMuted", snap.isMuted)
            .putInt("peerCount", Math.max(0, snap.peers.size()))
            .apply();
        VoiceWidget.refreshAll(this);
    }

    private void clearWidgetState() {
        getSharedPreferences(VoiceWidget.PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
        VoiceWidget.refreshAll(this);
    }

    public static Intent buildStartIntent(
        Context context,
        String roomId,
        String roomName,
        String userId,
        String username,
        String firebaseApiKey,
        String firebaseAppId,
        String firebaseProjectId,
        String firebaseStorageBucket,
        String firebaseMessagingSenderId
    ) {
        Intent i = new Intent(context, VoiceCallForegroundService.class);
        i.setAction(ACTION_START_CALL);
        i.putExtra(EXTRA_ROOM_ID, roomId);
        i.putExtra(EXTRA_ROOM_NAME, roomName);
        i.putExtra(EXTRA_USER_ID, userId);
        i.putExtra(EXTRA_USERNAME, username);
        i.putExtra(EXTRA_FIREBASE_API_KEY, firebaseApiKey);
        i.putExtra(EXTRA_FIREBASE_APP_ID, firebaseAppId);
        i.putExtra(EXTRA_FIREBASE_PROJECT_ID, firebaseProjectId);
        i.putExtra(EXTRA_FIREBASE_STORAGE_BUCKET, firebaseStorageBucket);
        i.putExtra(EXTRA_FIREBASE_MESSAGING_SENDER_ID, firebaseMessagingSenderId);
        return i;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        clearDisconnectAlertTasks();
        if (signalingClient != null) signalingClient.leaveRoom();
        if (webRtcManager != null) webRtcManager.closeAll();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User explicitly swiped the app task away: end call service and presence cleanly.
        notifyConnectionIssue("Call ended because app was closed");
        hangup();
        super.onTaskRemoved(rootIntent);
    }
}
