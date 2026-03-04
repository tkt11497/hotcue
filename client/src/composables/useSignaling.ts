import { ref, readonly } from "vue";
import { db, auth } from "../firebase";
import {
  collection,
  doc,
  setDoc,
  deleteDoc,
  getDoc,
  onSnapshot,
  addDoc,
  getDocs,
  serverTimestamp,
  query,
  where,
  Timestamp,
  type Unsubscribe,
} from "firebase/firestore";

export interface RoomUser {
  id: string;
  username: string;
  isMuted: boolean;
}

export interface SignalCallbacks {
  onPeerJoined: (peerId: string) => void;
  onPeerLeft: (peerId: string) => void;
  onSignal: (from: string, type: string, payload: any) => void;
}

const HEARTBEAT_INTERVAL_MS = 10_000;
const STALE_THRESHOLD_MS = 35_000;
const STALE_SWEEP_INTERVAL_MS = 30_000;

const connected = ref(false);
const roomId = ref<string | null>(null);
const users = ref<RoomUser[]>([]);
const myId = ref<string | null>(null);

export function useSignaling() {
  let unsubUsers: Unsubscribe | null = null;
  let unsubSignals: Unsubscribe | null = null;
  let callbacks: SignalCallbacks | null = null;
  let currentRoomId: string | null = null;
  let beforeUnloadHandler: (() => void) | null = null;
  let pagehideHandler: ((e: PageTransitionEvent) => void) | null = null;
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  let staleSweepTimer: ReturnType<typeof setInterval> | null = null;
  let unsubSelf: Unsubscribe | null = null;

  function startHeartbeat(usersCol: ReturnType<typeof collection>, uid: string, username: string) {
    heartbeatTimer = setInterval(() => {
      setDoc(doc(usersCol, uid), {
        username,
        userId: uid,
        lastSeen: serverTimestamp(),
      }, { merge: true }).catch(() => {});
    }, HEARTBEAT_INTERVAL_MS);
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  async function cleanupStaleUsers(usersCol: ReturnType<typeof collection>, myUid: string) {
    const snapshot = await getDocs(usersCol);
    const now = Date.now();
    for (const d of snapshot.docs) {
      if (d.id === myUid) continue;
      const data = d.data();
      const lastSeen = data.lastSeen as Timestamp | null;
      if (!lastSeen) {
        continue;
      }
      const age = now - lastSeen.toMillis();
      if (age > STALE_THRESHOLD_MS) {
        console.log(`[signaling] removing stale user ${d.id} (last seen ${Math.round(age / 1000)}s ago)`);
        await deleteDoc(d.ref).catch(() => {});
      }
    }
  }

  async function joinRoom(room: string, username: string, cbs: SignalCallbacks) {
    if (connected.value) return;

    const firebaseUser = auth.currentUser;
    if (!firebaseUser) throw new Error("Not authenticated");

    const uid = firebaseUser.uid;

    const userProfileSnap = await getDoc(doc(db, "users", uid));
    const profileData = userProfileSnap.data();
    const globalRole = profileData?.role;

    if (globalRole === "admin" || globalRole === "holding_admin") {
      // can join any room
    } else if (globalRole === "room_admin") {
      const roomSnap = await getDoc(doc(db, "rooms", room));
      const roomType = roomSnap.data()?.type;
      const userAssignedRoom = profileData?.assignedRoom;
      if (roomType !== "holding" && room !== userAssignedRoom) {
        throw new Error("You are not allowed in this room");
      }
    } else if (globalRole === "security_admin") {
      const roomSnap = await getDoc(doc(db, "rooms", room));
      const roomType = roomSnap.data()?.type;
      if (roomType !== "security" && roomType !== "holding") {
        throw new Error("You are not allowed in this room");
      }
    } else if (globalRole === "security") {
      const roomSnap = await getDoc(doc(db, "rooms", room));
      const roomType = roomSnap.data()?.type;
      if (roomType !== "security") {
        throw new Error("You are not allowed in this room");
      }
    } else {
      const allowedSnap = await getDoc(doc(db, "rooms", room, "allowed", uid));
      if (!allowedSnap.exists()) throw new Error("You are not allowed in this room");
    }

    callbacks = cbs;
    myId.value = uid;
    currentRoomId = room;

    const usersCol = collection(db, `rooms/${room}/users`);
    const signalsCol = collection(db, `rooms/${room}/signals`);

    await cleanupStaleUsers(usersCol, uid);

    unsubSignals = onSnapshot(
      query(signalsCol, where("to", "==", uid)),
      (snapshot) => {
        for (const change of snapshot.docChanges()) {
          if (change.type === "added") {
            const data = change.doc.data();
            callbacks?.onSignal(data.from, data.type, data.payload);
            deleteDoc(change.doc.ref).catch(() => {});
          }
        }
      }
    );

    await setDoc(doc(usersCol, uid), {
      username,
      userId: uid,
      isMuted: false,
      joinedAt: serverTimestamp(),
      lastSeen: serverTimestamp(),
    });

    startHeartbeat(usersCol, uid, username);

    unsubSelf = onSnapshot(doc(usersCol, uid), (snap) => {
      if (!snap.exists() && connected.value) {
        console.warn("[signaling] my presence doc was deleted externally, re-writing");
        setDoc(doc(usersCol, uid), {
          username,
          userId: uid,
          joinedAt: serverTimestamp(),
          lastSeen: serverTimestamp(),
        }).catch(() => {});
      }
    });

    let initialSnapshot = true;
    const knownPeers = new Set<string>();

    unsubUsers = onSnapshot(usersCol, (snapshot) => {
      const now = Date.now();
      const currentUsers: RoomUser[] = [];
      snapshot.forEach((d) => {
        const data = d.data();
        const lastSeen = data.lastSeen as Timestamp | null;
        if (lastSeen && d.id !== uid) {
          const age = now - lastSeen.toMillis();
          if (age > STALE_THRESHOLD_MS) {
            deleteDoc(d.ref).catch(() => {});
            return;
          }
        }
        if (!data.username) return;
        currentUsers.push({ id: d.id, username: data.username, isMuted: data.isMuted ?? false });
      });
      users.value = currentUsers;

      if (initialSnapshot) {
        initialSnapshot = false;
        snapshot.forEach((d) => {
          if (d.id !== uid) knownPeers.add(d.id);
        });
        return;
      }

      for (const change of snapshot.docChanges()) {
        if (change.doc.id === uid) continue;
        if (change.type === "added" && !knownPeers.has(change.doc.id)) {
          knownPeers.add(change.doc.id);
          callbacks?.onPeerJoined(change.doc.id);
        }
        if (change.type === "removed") {
          knownPeers.delete(change.doc.id);
          callbacks?.onPeerLeft(change.doc.id);
        }
      }
    });

    connected.value = true;
    roomId.value = room;

    beforeUnloadHandler = () => {
      deleteDoc(doc(usersCol, uid)).catch(() => {});
    };
    window.addEventListener("beforeunload", beforeUnloadHandler);

    pagehideHandler = (e: PageTransitionEvent) => {
      if (!e.persisted) {
        deleteDoc(doc(usersCol, uid)).catch(() => {});
      }
    };
    window.addEventListener("pagehide", pagehideHandler);

    staleSweepTimer = setInterval(() => {
      cleanupStaleUsers(usersCol, uid);
    }, STALE_SWEEP_INTERVAL_MS);
  }

  async function sendSignal(to: string, type: string, payload: any) {
    if (!currentRoomId || !myId.value) return;
    const signalsCol = collection(db, `rooms/${currentRoomId}/signals`);
    await addDoc(signalsCol, {
      from: myId.value,
      to,
      type,
      payload,
      createdAt: serverTimestamp(),
    });
  }

  async function leaveRoom() {
    if (beforeUnloadHandler) {
      window.removeEventListener("beforeunload", beforeUnloadHandler);
      beforeUnloadHandler = null;
    }
    if (pagehideHandler) {
      window.removeEventListener("pagehide", pagehideHandler);
      pagehideHandler = null;
    }
    if (staleSweepTimer) {
      clearInterval(staleSweepTimer);
      staleSweepTimer = null;
    }

    stopHeartbeat();

    unsubSelf?.();
    unsubUsers?.();
    unsubSignals?.();
    unsubSelf = null;
    unsubUsers = null;
    unsubSignals = null;

    if (currentRoomId && myId.value) {
      const usersCol = collection(db, `rooms/${currentRoomId}/users`);
      await deleteDoc(doc(usersCol, myId.value)).catch(() => {});
    }

    connected.value = false;
    roomId.value = null;
    users.value = [];
    myId.value = null;
    currentRoomId = null;
    callbacks = null;
  }

  async function updateMuteState(muted: boolean) {
    if (!currentRoomId || !myId.value) return;
    const usersCol = collection(db, `rooms/${currentRoomId}/users`);
    await setDoc(doc(usersCol, myId.value), { isMuted: muted }, { merge: true }).catch(() => {});
  }

  async function removePeerDoc(peerId: string) {
    if (!currentRoomId) return;
    const usersCol = collection(db, `rooms/${currentRoomId}/users`);
    console.log(`[signaling] removing ghost peer doc: ${peerId}`);
    await deleteDoc(doc(usersCol, peerId)).catch(() => {});
  }

  return {
    connected: readonly(connected),
    roomId: readonly(roomId),
    users: readonly(users),
    myId: readonly(myId),
    joinRoom,
    leaveRoom,
    sendSignal,
    updateMuteState,
    removePeerDoc,
  };
}
