import { readFileSync } from "fs";
import { createServer } from "https";
import { Server } from "socket.io";
import { fileURLToPath } from "url";
import { dirname, join } from "path";

const __dirname = dirname(fileURLToPath(import.meta.url));

const PORT = process.env.PORT || 3001;

const httpsServer = createServer({
  key: readFileSync(join(__dirname, "certs", "key.pem")),
  cert: readFileSync(join(__dirname, "certs", "cert.pem")),
});

const io = new Server(httpsServer, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"],
  },
});

const rooms = new Map();

function getRoomUsers(roomId) {
  const room = rooms.get(roomId) || new Map();
  return Array.from(room.values());
}

function broadcastRoomUsers(roomId) {
  io.to(roomId).emit("room:users", getRoomUsers(roomId));
}

io.on("connection", (socket) => {
  console.log(`[connect] ${socket.id}`);

  let currentRoom = null;
  let currentUser = null;

  socket.on("room:join", ({ roomId, username }) => {
    if (currentRoom) {
      socket.leave(currentRoom);
      const room = rooms.get(currentRoom);
      if (room) {
        room.delete(socket.id);
        if (room.size === 0) rooms.delete(currentRoom);
        else broadcastRoomUsers(currentRoom);
      }
    }

    currentRoom = roomId;
    currentUser = { id: socket.id, username };

    if (!rooms.has(roomId)) rooms.set(roomId, new Map());
    rooms.get(roomId).set(socket.id, currentUser);

    socket.join(roomId);
    console.log(`[join] ${username} -> room "${roomId}"`);

    socket.emit("room:joined", {
      roomId,
      users: getRoomUsers(roomId),
    });

    socket.to(roomId).emit("peer:joined", currentUser);
    broadcastRoomUsers(roomId);
  });

  socket.on("room:leave", () => {
    if (!currentRoom) return;
    handleLeave();
  });

  socket.on("signal:offer", ({ to, offer }) => {
    console.log(`[signal] offer ${socket.id} -> ${to} (sdp length: ${offer?.sdp?.length || 0})`);
    io.to(to).emit("signal:offer", { from: socket.id, offer });
  });

  socket.on("signal:answer", ({ to, answer }) => {
    console.log(`[signal] answer ${socket.id} -> ${to} (sdp length: ${answer?.sdp?.length || 0})`);
    io.to(to).emit("signal:answer", { from: socket.id, answer });
  });

  socket.on("signal:ice-candidate", ({ to, candidate }) => {
    console.log(`[signal] ice-candidate ${socket.id} -> ${to}`);
    io.to(to).emit("signal:ice-candidate", { from: socket.id, candidate });
  });

  socket.on("disconnect", () => {
    console.log(`[disconnect] ${socket.id}`);
    handleLeave();
  });

  function handleLeave() {
    if (!currentRoom) return;
    const room = rooms.get(currentRoom);
    if (room) {
      room.delete(socket.id);
      if (room.size === 0) {
        rooms.delete(currentRoom);
      } else {
        socket.to(currentRoom).emit("peer:left", { id: socket.id });
        broadcastRoomUsers(currentRoom);
      }
    }
    socket.leave(currentRoom);
    console.log(`[leave] ${currentUser?.username} left room "${currentRoom}"`);
    currentRoom = null;
    currentUser = null;
  }
});

httpsServer.listen(PORT, "0.0.0.0", () => {
  console.log(`\n🎙️  GCN Voice signaling server running`);
  console.log(`   https://localhost:${PORT}`);
  console.log(`   Listening on all interfaces (LAN accessible)\n`);
});
