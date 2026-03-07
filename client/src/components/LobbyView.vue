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
  box-shadow: 0 0 30px rgba(0, 0, 0, 0.5);
  position: relative;
  overflow: hidden;
}

.lobby-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; height: 2px;
  background: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.lobby-icon {
  color: var(--primary);
  margin-bottom: 16px;
}

.brand-logo {
  width: 76px;
  height: 76px;
  object-fit: contain;
  border-radius: var(--radius-sm);
  margin-bottom: 14px;
  box-shadow: 0 0 20px var(--primary-glow);
}

h2 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1.8rem;
  font-weight: 700;
  margin-bottom: 6px;
  color: var(--primary);
  text-transform: uppercase;
  letter-spacing: 1px;
  text-shadow: 0 0 10px var(--primary-glow);
}

.subtitle {
  color: var(--text-muted);
  font-size: 0.95rem;
  margin-bottom: 20px;
}

.brand-credit {
  font-family: "Rajdhani", sans-serif;
  color: var(--primary);
  font-size: 0.85rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 8px;
}

.error-msg {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 0, 85, 0.1);
  border: 1px solid rgba(255, 0, 85, 0.3);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.85rem;
  color: var(--danger);
  margin-bottom: 16px;
  text-align: left;
  box-shadow: inset 0 0 10px rgba(255, 0, 85, 0.1);
}

.loading-text,
.empty-text {
  color: var(--text-muted);
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-size: 1rem;
  padding: 24px 0;
}

.room-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
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
  font-family: "Rajdhani", sans-serif;
  font-size: 1.1rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
  position: relative;
  overflow: hidden;
}

.room-item:hover:not(:disabled) {
  border-color: var(--primary);
  background: rgba(0, 255, 136, 0.05);
  box-shadow: 0 0 15px var(--primary-glow);
  color: var(--primary);
  transform: translateY(-2px);
}

.room-item:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.room-name {
  flex: 1;
}

.join-arrow {
  color: var(--text-muted);
  transition: transform 0.2s, color 0.2s;
}

.room-item:hover:not(:disabled) .join-arrow {
  transform: translateX(4px);
  color: var(--primary);
}

.reliability-link {
  display: inline-block;
  margin-top: 20px;
  color: var(--text-muted);
  font-size: 0.85rem;
  transition: color 0.2s;
}

.reliability-link:hover {
  color: var(--primary);
  text-shadow: 0 0 8px var(--primary-glow);
}
</style>
