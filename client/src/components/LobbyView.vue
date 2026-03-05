<script setup lang="ts">
import { ref, watch } from "vue";
import { useAuth } from "../composables/useAuth";
import { db } from "../firebase";
import { collection, getDocs, doc, getDoc } from "firebase/firestore";

const { userProfile, canAccessAllRooms } = useAuth();
let roomsLoaded = false;

const props = defineProps<{
  error: string | null;
  disconnectInfo: string | null;
  connecting: boolean;
}>();

const emit = defineEmits<{
  join: [roomId: string];
  "dismiss-disconnect": [];
}>();

interface RoomEntry {
  id: string;
  name: string;
}

const rooms = ref<RoomEntry[]>([]);
const loading = ref(true);

watch(userProfile, async (profile) => {
  if (!profile || roomsLoaded) return;
  roomsLoaded = true;
  try {
    const roomsSnap = await getDocs(collection(db, "rooms"));
    const allowed: RoomEntry[] = [];

    for (const roomDoc of roomsSnap.docs) {
      const data = roomDoc.data();
      const entry: RoomEntry = { id: roomDoc.id, name: data.name || roomDoc.id };

      if (canAccessAllRooms.value) {
        allowed.push(entry);
      } else if (profile.role === "room_admin") {
        if (roomDoc.id === profile.assignedRoom || data.type === "holding") {
          allowed.push(entry);
        }
      } else if (profile.role === "security_admin") {
        if (data.type === "security" || data.type === "holding") {
          allowed.push(entry);
        }
      } else if (profile.role === "security") {
        if (data.type === "security") {
          allowed.push(entry);
        }
      } else {
        const snap = await getDoc(doc(db, "rooms", roomDoc.id, "allowed", profile.uid));
        if (snap.exists()) allowed.push(entry);
      }
    }
    rooms.value = allowed;
  } catch (err) {
    console.error("[lobby] failed to load rooms:", err);
  } finally {
    loading.value = false;
  }
}, { immediate: true });

function joinRoom(roomId: string) {
  emit("join", roomId);
}
</script>

<template>
  <div class="lobby">
    <div class="lobby-card">
      <div class="lobby-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
          <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          <line x1="12" y1="19" x2="12" y2="23" />
          <line x1="8" y1="23" x2="16" y2="23" />
        </svg>
      </div>
      <h2>Voice Rooms</h2>
      <p class="subtitle">Welcome, {{ userProfile?.displayName }}</p>

      <div v-if="disconnectInfo" class="disconnect-msg">
        <div class="disconnect-content">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91" />
            <line x1="23" y1="1" x2="1" y2="23" />
          </svg>
          <span>{{ disconnectInfo }}</span>
        </div>
        <button class="dismiss-btn" @click="emit('dismiss-disconnect')" title="Dismiss">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>

      <div v-if="error" class="error-msg">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10" />
          <line x1="15" y1="9" x2="9" y2="15" />
          <line x1="9" y1="9" x2="15" y2="15" />
        </svg>
        {{ error }}
      </div>

      <div v-if="loading" class="loading-text">Loading rooms...</div>

      <div v-else-if="rooms.length === 0" class="empty-text">
        No rooms available. Ask an admin to add you to a room.
      </div>

      <div v-else class="room-list">
        <button
          v-for="room in rooms"
          :key="room.id"
          class="room-item"
          :disabled="connecting"
          @click="joinRoom(room.id)"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          <span class="room-name">{{ room.name }}</span>
          <svg class="join-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lobby {
  width: 100%;
  max-width: 480px;
}

.lobby-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 40px 32px;
  text-align: center;
}

.lobby-icon {
  color: var(--primary);
  margin-bottom: 16px;
}

h2 {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 6px;
}

.subtitle {
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 24px;
}

.disconnect-msg {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: rgba(243, 156, 18, 0.1);
  border: 1px solid rgba(243, 156, 18, 0.35);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.85rem;
  color: #f39c12;
  margin-bottom: 16px;
  text-align: left;
  animation: slide-in 0.3s ease;
}

.disconnect-content {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dismiss-btn {
  background: none;
  border: none;
  color: #f39c12;
  cursor: pointer;
  padding: 2px;
  opacity: 0.7;
  transition: opacity 0.2s;
  flex-shrink: 0;
}

.dismiss-btn:hover {
  opacity: 1;
}

@keyframes slide-in {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}

.error-msg {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.85rem;
  color: var(--danger);
  margin-bottom: 16px;
  text-align: left;
}

.loading-text,
.empty-text {
  color: var(--text-muted);
  font-size: 0.9rem;
  padding: 24px 0;
}

.room-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.room-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 14px 16px;
  color: var(--text);
  font-size: 0.95rem;
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
}

.room-item:hover:not(:disabled) {
  border-color: var(--primary);
  background: var(--surface-hover);
}

.room-item:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.room-name {
  flex: 1;
  font-weight: 600;
}

.join-arrow {
  color: var(--text-muted);
  transition: transform 0.2s;
}

.room-item:hover:not(:disabled) .join-arrow {
  transform: translateX(3px);
  color: var(--primary);
}
</style>
