package com.gcn.voice.call;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NativeSignalingClient {

    public interface Callback {
        void onPeerJoined(String peerId);
        void onPeerLeft(String peerId);
        void onSignal(String from, String type, Map<String, Object> payload);
        void onUsers(List<CallStateStore.UserSnapshot> users);
        void onError(String message);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final long STALE_THRESHOLD_MS = 35_000L;
    private static final long STALE_SWEEP_INTERVAL_MS = 30_000L;
    private static final long MAX_SIGNAL_AGE_MS = 60_000L;

    private final FirebaseFirestore db;
    private final Callback callback;

    private ListenerRegistration usersRegistration;
    private ListenerRegistration signalsRegistration;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService staleSweepExecutor;
    private String roomId;
    private String myId;
    private String myUsername;

    public NativeSignalingClient(Context context, Callback callback) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context);
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw new IllegalStateException(
                "FirebaseApp is not initialized. Ensure google-services.json exists under android/app and google-services plugin is applied."
            );
        }
        this.db = FirebaseFirestore.getInstance(FirebaseApp.getInstance());
        this.callback = callback;
    }

    public NativeSignalingClient(
        Context context,
        Callback callback,
        String apiKey,
        String appId,
        String projectId,
        String storageBucket,
        String messagingSenderId
    ) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context);
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            if (apiKey != null && appId != null && projectId != null) {
                FirebaseOptions.Builder builder = new FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(appId)
                    .setProjectId(projectId);
                if (storageBucket != null && !storageBucket.isEmpty()) {
                    builder.setStorageBucket(storageBucket);
                }
                if (messagingSenderId != null && !messagingSenderId.isEmpty()) {
                    builder.setGcmSenderId(messagingSenderId);
                }
                FirebaseApp.initializeApp(context, builder.build());
            }
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw new IllegalStateException(
                "FirebaseApp is not initialized. Provide google-services.json or pass Firebase options to native startCall."
            );
        }
        this.db = FirebaseFirestore.getInstance(FirebaseApp.getInstance());
        this.callback = callback;
    }

    public void joinRoom(String roomId, String myId, String myUsername) {
        this.roomId = roomId;
        this.myId = myId;
        this.myUsername = myUsername;

        CollectionReference usersCol = db.collection("rooms").document(roomId).collection("users");
        CollectionReference signalsCol = db.collection("rooms").document(roomId).collection("signals");

        // Drain pending messages from previous crashed/abandoned sessions before listening.
        cleanupPendingSignals(signalsCol, myId);
        cleanupStaleUsers(usersCol, myId);

        Map<String, Object> me = new HashMap<>();
        me.put("username", myUsername);
        me.put("userId", myId);
        me.put("isMuted", false);
        me.put("joinedAt", FieldValue.serverTimestamp());
        me.put("lastSeen", FieldValue.serverTimestamp());
        usersCol.document(myId).set(me)
            .addOnFailureListener(err -> callback.onError("Failed to join room: " + err.getMessage()));

        signalsRegistration = signalsCol.whereEqualTo("to", myId)
            .addSnapshotListener((snap, err) -> {
                if (err != null) {
                    callback.onError("Signal listener error: " + err.getMessage());
                    return;
                }
                if (snap == null) return;
                for (DocumentSnapshot doc : snap.getDocuments()) {
                    Object fromObj = doc.get("from");
                    Object typeObj = doc.get("type");
                    Object payloadObj = doc.get("payload");
                    Timestamp createdAt = doc.getTimestamp("createdAt");
                    boolean stale = false;
                    if (createdAt != null) {
                        long age = System.currentTimeMillis() - createdAt.toDate().getTime();
                        stale = age > MAX_SIGNAL_AGE_MS;
                    }

                    if (!stale && (fromObj instanceof String) && (typeObj instanceof String)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = payloadObj instanceof Map ? (Map<String, Object>) payloadObj : new HashMap<>();
                        callback.onSignal((String) fromObj, (String) typeObj, payload);
                    }

                    // Always delete handled/invalid/stale signal docs to avoid pile-up.
                    doc.getReference().delete();
                }
            });

        usersRegistration = usersCol.addSnapshotListener(new EventListener<QuerySnapshot>() {
            private final List<String> knownPeers = new ArrayList<>();

            @Override
            public void onEvent(@Nullable QuerySnapshot snap, @Nullable com.google.firebase.firestore.FirebaseFirestoreException err) {
                if (err != null) {
                    callback.onError("Users listener error: " + err.getMessage());
                    return;
                }
                if (snap == null) return;

                long now = System.currentTimeMillis();
                List<CallStateStore.UserSnapshot> users = new ArrayList<>();
                List<String> currentPeers = new ArrayList<>();

                for (DocumentSnapshot d : snap.getDocuments()) {
                    String id = d.getId();
                    String username = d.getString("username");
                    Boolean isMuted = d.getBoolean("isMuted");
                    Timestamp lastSeen = d.getTimestamp("lastSeen");
                    if (username == null) continue;

                    if (!id.equals(myId) && lastSeen != null) {
                        long age = now - lastSeen.toDate().getTime();
                        if (age > STALE_THRESHOLD_MS) {
                            d.getReference().delete();
                            continue;
                        }
                    }

                    users.add(new CallStateStore.UserSnapshot(id, username, isMuted != null && isMuted));
                    if (!id.equals(myId)) currentPeers.add(id);
                }

                for (String id : currentPeers) {
                    if (!knownPeers.contains(id)) callback.onPeerJoined(id);
                }
                for (String id : new ArrayList<>(knownPeers)) {
                    if (!currentPeers.contains(id)) callback.onPeerLeft(id);
                }

                knownPeers.clear();
                knownPeers.addAll(currentPeers);
                callback.onUsers(users);
            }
        });

        startHeartbeat(usersCol);
        startStaleSweep(usersCol, myId);
    }

    private void startHeartbeat(CollectionReference usersCol) {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (myId == null || myUsername == null) return;
            Map<String, Object> hb = new HashMap<>();
            hb.put("username", myUsername);
            hb.put("userId", myId);
            hb.put("lastSeen", FieldValue.serverTimestamp());
            usersCol.document(myId).set(hb, SetOptions.merge());
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void startStaleSweep(CollectionReference usersCol, String myUid) {
        stopStaleSweep();
        staleSweepExecutor = Executors.newSingleThreadScheduledExecutor();
        staleSweepExecutor.scheduleAtFixedRate(
            () -> cleanupStaleUsers(usersCol, myUid),
            STALE_SWEEP_INTERVAL_MS,
            STALE_SWEEP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private void stopStaleSweep() {
        if (staleSweepExecutor != null) {
            staleSweepExecutor.shutdownNow();
            staleSweepExecutor = null;
        }
    }

    private void cleanupPendingSignals(CollectionReference signalsCol, String uid) {
        signalsCol.whereEqualTo("to", uid).get()
            .addOnSuccessListener(snapshot -> {
                for (DocumentSnapshot d : snapshot.getDocuments()) {
                    d.getReference().delete();
                }
            })
            .addOnFailureListener(err -> callback.onError("Failed to cleanup pending signals: " + err.getMessage()));
    }

    private void cleanupStaleUsers(CollectionReference usersCol, String myUid) {
        usersCol.get()
            .addOnSuccessListener(snapshot -> {
                long now = System.currentTimeMillis();
                for (DocumentSnapshot d : snapshot.getDocuments()) {
                    if (myUid.equals(d.getId())) continue;
                    Timestamp lastSeen = d.getTimestamp("lastSeen");
                    if (lastSeen == null) continue;
                    long age = now - lastSeen.toDate().getTime();
                    if (age > STALE_THRESHOLD_MS) {
                        d.getReference().delete();
                    }
                }
            })
            .addOnFailureListener(err -> callback.onError("Failed stale user sweep: " + err.getMessage()));
    }

    public void setMuted(boolean muted) {
        if (roomId == null || myId == null) return;
        db.collection("rooms").document(roomId).collection("users").document(myId)
            .set(CollectionsUtil.mapOf("isMuted", muted), SetOptions.merge());
    }

    public void sendSignal(String to, String type, Map<String, Object> payload) {
        if (roomId == null || myId == null) return;
        Map<String, Object> out = new HashMap<>();
        out.put("from", myId);
        out.put("to", to);
        out.put("type", type);
        out.put("payload", payload == null ? new HashMap<>() : payload);
        out.put("createdAt", FieldValue.serverTimestamp());
        db.collection("rooms").document(roomId).collection("signals").add(out)
            .addOnFailureListener(err -> callback.onError("Failed to send signal: " + err.getMessage()));
    }

    public void leaveRoom() {
        stopHeartbeat();
        stopStaleSweep();
        if (usersRegistration != null) {
            usersRegistration.remove();
            usersRegistration = null;
        }
        if (signalsRegistration != null) {
            signalsRegistration.remove();
            signalsRegistration = null;
        }
        if (roomId != null && myId != null) {
            db.collection("rooms").document(roomId).collection("users").document(myId).delete();
        }
        roomId = null;
        myId = null;
        myUsername = null;
    }
}
