package com.gcn.voice.call;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CallStateStore {

    public static class PeerSnapshot {
        public final String peerId;
        public final String connectionState;
        public final String iceState;
        public final int rttMs;
        public final int jitterMs;
        public final int packetsLost;
        public final int remoteTrackCount;

        public PeerSnapshot(
            String peerId,
            String connectionState,
            String iceState,
            int rttMs,
            int jitterMs,
            int packetsLost,
            int remoteTrackCount
        ) {
            this.peerId = peerId;
            this.connectionState = connectionState;
            this.iceState = iceState;
            this.rttMs = rttMs;
            this.jitterMs = jitterMs;
            this.packetsLost = packetsLost;
            this.remoteTrackCount = remoteTrackCount;
        }
    }

    public static class UserSnapshot {
        public final String id;
        public final String username;
        public final boolean isMuted;

        public UserSnapshot(String id, String username, boolean isMuted) {
            this.id = id;
            this.username = username;
            this.isMuted = isMuted;
        }
    }

    public static class Snapshot {
        public final boolean inCall;
        public final boolean isMuted;
        public final boolean connected;
        public final String roomId;
        public final String myId;
        public final String myUsername;
        public final long startedAtEpochMs;
        public final List<UserSnapshot> users;
        public final List<PeerSnapshot> peers;

        public Snapshot(
            boolean inCall,
            boolean isMuted,
            boolean connected,
            String roomId,
            String myId,
            String myUsername,
            long startedAtEpochMs,
            List<UserSnapshot> users,
            List<PeerSnapshot> peers
        ) {
            this.inCall = inCall;
            this.isMuted = isMuted;
            this.connected = connected;
            this.roomId = roomId;
            this.myId = myId;
            this.myUsername = myUsername;
            this.startedAtEpochMs = startedAtEpochMs;
            this.users = users;
            this.peers = peers;
        }
    }

    private boolean inCall = false;
    private boolean isMuted = false;
    private boolean connected = false;
    private String roomId = null;
    private String myId = null;
    private String myUsername = null;
    private long startedAtEpochMs = 0L;
    private final Map<String, PeerSnapshot> peers = new HashMap<>();
    private final Map<String, UserSnapshot> users = new HashMap<>();

    public synchronized void beginCall(String roomId, String myId, String myUsername) {
        this.inCall = true;
        this.connected = true;
        this.roomId = roomId;
        this.myId = myId;
        this.myUsername = myUsername;
        this.startedAtEpochMs = System.currentTimeMillis();
        this.peers.clear();
        this.users.clear();
        this.users.put(myId, new UserSnapshot(myId, myUsername, this.isMuted));
    }

    public synchronized void endCall() {
        this.inCall = false;
        this.connected = false;
        this.roomId = null;
        this.myId = null;
        this.myUsername = null;
        this.startedAtEpochMs = 0L;
        this.peers.clear();
        this.users.clear();
    }

    public synchronized void setConnected(boolean connected) {
        this.connected = connected;
    }

    public synchronized void setMuted(boolean muted) {
        this.isMuted = muted;
        if (this.myId != null && this.users.containsKey(this.myId)) {
            UserSnapshot self = this.users.get(this.myId);
            this.users.put(this.myId, new UserSnapshot(self.id, self.username, muted));
        }
    }

    public synchronized boolean isMuted() {
        return isMuted;
    }

    public synchronized void upsertPeer(PeerSnapshot peer) {
        this.peers.put(peer.peerId, peer);
    }

    public synchronized void removePeer(String peerId) {
        this.peers.remove(peerId);
    }

    public synchronized void setUsers(List<UserSnapshot> usersList) {
        this.users.clear();
        for (UserSnapshot user : usersList) {
            this.users.put(user.id, user);
        }
        if (this.myId != null && !this.users.containsKey(this.myId)) {
            this.users.put(this.myId, new UserSnapshot(this.myId, this.myUsername == null ? "Me" : this.myUsername, this.isMuted));
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            inCall,
            isMuted,
            connected,
            roomId,
            myId,
            myUsername,
            startedAtEpochMs,
            Collections.unmodifiableList(new ArrayList<>(users.values())),
            Collections.unmodifiableList(new ArrayList<>(peers.values()))
        );
    }
}
