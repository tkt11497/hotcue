<script setup lang="ts">
import { ref, watch } from "vue";
import { useAuth } from "../composables/useAuth";
import { db } from "../firebase";
import { collection, getDocs, doc, getDoc } from "firebase/firestore";

const { userProfile, canAccessAllRooms } = useAuth();
let roomsLoaded = false;

const props = defineProps<{
  error: string | null;
  connecting: boolean;
}>();

const emit = defineEmits<{
  join: [roomId: string];
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
      } else if (profile.role === "member") {
        if (roomDoc.id === profile.assignedRoom) {
          allowed.push(entry);
          continue;
        }
        const snap = await getDoc(doc(db, "rooms", roomDoc.id, "allowed", profile.uid));
        if (snap.exists()) allowed.push(entry);
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
      <img src="/hotcue-logo.png" alt="Hot Cue logo" class="brand-logo" />
      <h2>Hot Cue Rooms</h2>
      <p class="brand-credit">Developed by GCN</p>
      <p class="subtitle">Welcome, {{ userProfile?.displayName }}</p>

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
      <RouterLink class="reliability-link" to="/call-reliability">Improve call reliability</RouterLink>
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

.brand-logo {
  width: 70px;
  height: 70px;
  object-fit: contain;
  border-radius: 12px;
  margin-bottom: 14px;
  box-shadow: 0 14px 28px rgba(0, 0, 0, 0.35);
}

h2 {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 6px;
}

.subtitle {
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 20px;
}

.brand-credit {
  color: rgba(228, 230, 237, 0.72);
  font-size: 0.8rem;
  margin-bottom: 4px;
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

.reliability-link {
  display: inline-block;
  margin-top: 14px;
  color: var(--text-muted);
  font-size: 0.85rem;
}
</style>
