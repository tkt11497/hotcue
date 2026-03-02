import { ref, readonly, type Ref } from "vue";
import { io, Socket } from "socket.io-client";

export interface RoomUser {
  id: string;
  username: string;
}

const socket = ref<Socket | null>(null);
const connected = ref(false);
const roomId = ref<string | null>(null);
const users = ref<RoomUser[]>([]);
const myId = ref<string | null>(null);

export function useSignaling() {
  function connect(serverUrl: string) {
    if (socket.value) return;

    const s = io(serverUrl, {
      transports: ["websocket"],
      rejectUnauthorized: false,
    });

    s.on("connect", () => {
      connected.value = true;
      myId.value = s.id ?? null;
    });

    s.on("disconnect", () => {
      connected.value = false;
      roomId.value = null;
      users.value = [];
    });

    s.on("room:joined", (data: { roomId: string; users: RoomUser[] }) => {
      roomId.value = data.roomId;
      users.value = data.users;
    });

    s.on("room:users", (list: RoomUser[]) => {
      users.value = list;
    });

    socket.value = s;
  }

  function joinRoom(room: string, username: string) {
    socket.value?.emit("room:join", { roomId: room, username });
  }

  function leaveRoom() {
    socket.value?.emit("room:leave");
    roomId.value = null;
    users.value = [];
  }

  function disconnect() {
    socket.value?.disconnect();
    socket.value = null;
    connected.value = false;
    roomId.value = null;
    users.value = [];
    myId.value = null;
  }

  function getRawSocket(): Socket | null {
    return socket.value as Socket | null;
  }

  return {
    socket: socket as Readonly<Ref<Socket | null>>,
    connected: readonly(connected),
    roomId: readonly(roomId),
    users: readonly(users),
    myId: readonly(myId),
    connect,
    joinRoom,
    leaveRoom,
    disconnect,
    getRawSocket,
  };
}
