import { computed, ref, readonly } from "vue";
import { db, auth } from "../firebase";
import { doc, getDoc } from "firebase/firestore";
import { useNativeCallBridge } from "./useNativeCallBridge";

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

const connected = ref(false);
const roomId = ref<string | null>(null);
const users = ref<RoomUser[]>([]);
const myId = ref<string | null>(null);

export function useSignaling() {
  const nativeCall = useNativeCallBridge();
  const knownPeers = new Set<string>();
  let callbacks: SignalCallbacks | null = null;

  nativeCall.ensureListener().catch(() => {});

  const syncTimer = setInterval(() => {
    const state = nativeCall.state;
    connected.value = !!state.connected;
    roomId.value = state.roomId ?? null;
    myId.value = state.myId ?? null;
    users.value = state.users.map((u) => ({
      id: u.id,
      username: u.username,
      isMuted: !!u.isMuted,
    }));

    const currentPeers = new Set(
      state.users
        .map((u) => u.id)
        .filter((id) => !!id && id !== state.myId)
    );
    for (const id of currentPeers) {
      if (!knownPeers.has(id)) callbacks?.onPeerJoined(id);
    }
    for (const id of knownPeers) {
      if (!currentPeers.has(id)) callbacks?.onPeerLeft(id);
    }
    knownPeers.clear();
    for (const id of currentPeers) knownPeers.add(id);
  }, 500);

  async function joinRoom(room: string, username: string, cbs: SignalCallbacks, roomName?: string) {
    if (connected.value) return;
    callbacks = cbs;

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

    await nativeCall.startCall(room, roomName || room, uid, username);
  }

  async function sendSignal(to: string, type: string, payload: any) {
    // Signaling is fully owned by native service.
    void to;
    void type;
    void payload;
  }

  async function leaveRoom() {
    callbacks = null;
    knownPeers.clear();
    await nativeCall.hangup();
    connected.value = false;
    roomId.value = null;
    users.value = [];
    myId.value = null;
  }

  async function updateMuteState(muted: boolean) {
    void muted;
  }

  async function removePeerDoc(peerId: string) {
    void peerId;
  }

  return {
    connected: readonly(connected),
    roomId: readonly(roomId),
    roomName: computed(() => nativeCall.state.roomName || roomId.value || ""),
    users: readonly(users),
    myId: readonly(myId),
    callPhase: computed(() => nativeCall.state.callPhase || "idle"),
    nativeReady: computed(() => nativeCall.ready.value),
    joinRoom,
    leaveRoom,
    sendSignal,
    updateMuteState,
    removePeerDoc,
    _dispose() {
      clearInterval(syncTimer);
    },
  };
}
