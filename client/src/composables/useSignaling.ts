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
}

export interface SignalCallbacks {
  onPeerJoined: (peerId: string) => void;
  onPeerLeft: (peerId: string) => void;
  onSignal: (from: string, type: string, payload: any) => void;
}

const HEARTBEAT_INTERVAL_MS = 10_000;
const STALE_THRESHOLD_MS = 50_000;

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
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

  function startHeartbeat(usersCol: ReturnType<typeof collection>, uid: string) {
    heartbeatTimer = setInterval(() => {
      setDoc(doc(usersCol, uid), { lastSeen: serverTimestamp() }, { merge: true }).catch(() => {});
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
      joinedAt: serverTimestamp(),
      lastSeen: serverTimestamp(),
    });

    startHeartbeat(usersCol, uid);

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
        currentUsers.push({ id: d.id, username: data.username });
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

    stopHeartbeat();

    unsubUsers?.();
    unsubSignals?.();
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

  return {
    connected: readonly(connected),
    roomId: readonly(roomId),
    users: readonly(users),
    myId: readonly(myId),
    joinRoom,
    leaveRoom,
    sendSignal,
  };
}
