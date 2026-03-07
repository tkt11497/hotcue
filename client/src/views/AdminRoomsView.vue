<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { db } from "../firebase";
import {
  collection,
  getDocs,
  addDoc,
  deleteDoc,
  doc,
  setDoc,
  serverTimestamp,
} from "firebase/firestore";
import { useAuth } from "../composables/useAuth";

const { userProfile, isAdmin } = useAuth();

interface RoomRow {
  id: string;
  name: string;
  type: "regular" | "holding" | "security";
}

interface AllowedUser {
  uid: string;
  displayName: string;
  email: string;
  role: string;
}

interface UserOption {
  uid: string;
  displayName: string;
  email: string;
}

const rooms = ref<RoomRow[]>([]);
const loading = ref(true);
const newRoomName = ref("");
const newRoomType = ref<"regular" | "holding" | "security">("regular");

const selectedRoom = ref<string | null>(null);
const allowedUsers = ref<AllowedUser[]>([]);
const allUsers = ref<UserOption[]>([]);
const loadingMembers = ref(false);
const addUserUid = ref("");

const holdingExists = computed(() => rooms.value.some((r) => r.type === "holding"));
const securityExists = computed(() => rooms.value.some((r) => r.type === "security"));

onMounted(async () => {
  await loadRooms();
  const snap = await getDocs(collection(db, "users"));
  allUsers.value = snap.docs.map((d) => ({
    uid: d.id,
    displayName: d.data().displayName,
    email: d.data().email,
  }));
});

async function loadRooms() {
  loading.value = true;
  try {
    const snap = await getDocs(collection(db, "rooms"));
    rooms.value = snap.docs.map((d) => ({
      id: d.id,
      name: d.data().name || d.id,
      type: d.data().type || "regular",
    }));
  } finally {
    loading.value = false;
  }
}

async function createRoom() {
  if (!newRoomName.value.trim()) return;
  if (newRoomType.value === "holding" && holdingExists.value) return;
  if (newRoomType.value === "security" && securityExists.value) return;
  await addDoc(collection(db, "rooms"), {
    name: newRoomName.value.trim(),
    type: newRoomType.value,
    createdBy: userProfile.value?.uid,
    createdAt: serverTimestamp(),
  });
  newRoomName.value = "";
  newRoomType.value = "regular";
  await loadRooms();
}

async function deleteRoom(id: string) {
  if (!confirm("Delete this room?")) return;
  await deleteDoc(doc(db, "rooms", id));
  if (selectedRoom.value === id) {
    selectedRoom.value = null;
    allowedUsers.value = [];
  }
  await loadRooms();
}

async function selectRoom(id: string) {
  selectedRoom.value = id;
  loadingMembers.value = true;
  try {
    const snap = await getDocs(collection(db, "rooms", id, "allowed"));
    const userMap = new Map(allUsers.value.map((u) => [u.uid, u]));
    allowedUsers.value = snap.docs.map((d) => {
      const info = userMap.get(d.id);
      return {
        uid: d.id,
        displayName: info?.displayName ?? d.id,
        email: info?.email ?? "",
        role: d.data().role || "member",
      };
    });
  } finally {
    loadingMembers.value = false;
  }
}

async function addUserToRoom() {
  if (!addUserUid.value || !selectedRoom.value) return;
  await setDoc(doc(db, "rooms", selectedRoom.value, "allowed", addUserUid.value), {
    role: "member",
    addedBy: userProfile.value?.uid,
    addedAt: serverTimestamp(),
  });
  addUserUid.value = "";
  await selectRoom(selectedRoom.value);
}

async function removeUserFromRoom(uid: string) {
  if (!selectedRoom.value) return;
  await deleteDoc(doc(db, "rooms", selectedRoom.value, "allowed", uid));
  await selectRoom(selectedRoom.value);
}

const selectedRoomObj = computed(() => rooms.value.find((r) => r.id === selectedRoom.value));
</script>

<template>
  <div class="admin-rooms">
    <div class="rooms-panel">
      <h2>Rooms</h2>
      <p class="desc">Create and manage voice rooms.</p>

      <form v-if="isAdmin" @submit.prevent="createRoom" class="create-form">
        <input v-model="newRoomName" placeholder="New room name" class="input" />
        <select v-model="newRoomType" class="input type-select">
          <option value="regular">Regular</option>
          <option value="holding" :disabled="holdingExists">Holding Channel</option>
          <option value="security" :disabled="securityExists">Security Channel</option>
        </select>
        <button type="submit" class="btn-sm" :disabled="!newRoomName.trim() || (newRoomType === 'holding' && holdingExists) || (newRoomType === 'security' && securityExists)">Create</button>
      </form>

      <div v-if="loading" class="loading">Loading...</div>
      <div v-else-if="rooms.length === 0" class="empty">No rooms yet.</div>
      <div v-else class="room-list">
        <div
          v-for="room in rooms"
          :key="room.id"
          class="room-row"
          :class="{ active: selectedRoom === room.id }"
          @click="selectRoom(room.id)"
        >
          <div class="room-info">
            <span class="room-name">{{ room.name }}</span>
            <span v-if="room.type === 'holding'" class="holding-badge">HOLDING</span>
            <span v-if="room.type === 'security'" class="security-badge">SECURITY</span>
          </div>
          <button v-if="isAdmin" class="btn-delete" @click.stop="deleteRoom(room.id)" title="Delete room">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
      </div>
    </div>

    <div v-if="selectedRoom" class="members-panel">
      <h3>
        Members of "{{ selectedRoomObj?.name }}"
        <span v-if="selectedRoomObj?.type === 'holding'" class="holding-badge">HOLDING</span>
        <span v-if="selectedRoomObj?.type === 'security'" class="security-badge">SECURITY</span>
      </h3>
      <p v-if="selectedRoomObj?.type === 'holding'" class="hint">
        All holding admins and room admins can access this channel automatically.
        Add members below for explicit access.
      </p>
      <p v-if="selectedRoomObj?.type === 'security'" class="hint">
        All security admins and security users can access this channel automatically.
        Add members below for explicit access.
      </p>

      <form @submit.prevent="addUserToRoom" class="create-form">
        <select v-model="addUserUid" class="input">
          <option value="" disabled>Add user...</option>
          <option
            v-for="u in allUsers.filter((x) => !allowedUsers.some((a) => a.uid === x.uid))"
            :key="u.uid"
            :value="u.uid"
          >
            {{ u.displayName }} ({{ u.email }})
          </option>
        </select>
        <button type="submit" class="btn-sm" :disabled="!addUserUid">Add</button>
      </form>

      <div v-if="loadingMembers" class="loading">Loading members...</div>
      <div v-else-if="allowedUsers.length === 0" class="empty">No explicit members yet.</div>
      <table v-else class="data-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in allowedUsers" :key="u.uid">
            <td>
              {{ u.displayName }}
              <span class="muted">{{ u.email }}</span>
            </td>
            <td>
              <button class="btn-delete" @click="removeUserFromRoom(u.uid)" title="Remove">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.admin-rooms {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}

.rooms-panel,
.members-panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
  flex: 1;
  min-width: 280px;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.4);
}

@media (max-width: 768px) {
  .rooms-panel,
  .members-panel {
    padding: 16px;
    min-width: 100%;
  }
}

h2 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1.6rem;
  margin-bottom: 4px;
  color: var(--primary);
  text-shadow: 0 0 10px var(--primary-glow);
  text-transform: uppercase;
  letter-spacing: 1px;
}

h3 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1.3rem;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.desc {
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 16px;
}

.hint {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-bottom: 16px;
  font-style: italic;
  background: rgba(255, 255, 255, 0.03);
  padding: 8px 12px;
  border-left: 2px solid var(--primary);
  border-radius: var(--radius-sm);
}

.create-form {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

@media (max-width: 768px) {
  .create-form {
    flex-direction: column;
  }
}

.input {
  flex: 1;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-family: "Outfit", sans-serif;
  font-size: 0.95rem;
  color: var(--text);
  outline: none;
  transition: all 0.2s;
}

.type-select {
  flex: 0 0 auto;
  width: 160px;
}

@media (max-width: 768px) {
  .type-select {
    width: 100%;
  }
}

.input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.input::placeholder {
  color: var(--text-muted);
  opacity: 0.5;
}

.btn-sm {
  background: var(--primary);
  color: #000;
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  padding: 10px 16px;
  font-family: "Rajdhani", sans-serif;
  font-size: 0.95rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-sm:hover:not(:disabled) {
  background: #00e67a;
  transform: translateY(-2px);
  box-shadow: 0 0 15px var(--primary-glow);
}

.btn-sm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--surface);
  color: var(--text-muted);
  border-color: var(--border);
}

.loading,
.empty {
  color: var(--text-muted);
  font-family: "Rajdhani", sans-serif;
  font-size: 1rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  padding: 24px 0;
  text-align: center;
}

.room-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.room-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s;
}

.room-row:hover {
  background: rgba(0, 255, 136, 0.05);
  border-color: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
  transform: translateY(-2px);
}

.room-row.active {
  background: var(--primary);
  color: #000;
  border-color: var(--primary);
  box-shadow: 0 0 15px var(--primary-glow);
}

.room-row.active .room-name, 
.room-row.active .btn-delete {
  color: #000;
}

.room-row.active .btn-delete:hover {
  background: rgba(0, 0, 0, 0.1);
  color: #000;
}

.room-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.room-name {
  font-family: "Rajdhani", sans-serif;
  font-weight: 700;
  font-size: 1.1rem;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.holding-badge {
  font-family: "Rajdhani", sans-serif;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(243, 156, 18, 0.15);
  color: #f39c12;
  border: 1px solid rgba(243, 156, 18, 0.3);
}

.security-badge {
  font-family: "Rajdhani", sans-serif;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(155, 89, 182, 0.15);
  color: #9b59b6;
  border: 1px solid rgba(155, 89, 182, 0.3);
}

.room-row.active .holding-badge,
.room-row.active .security-badge {
  background: rgba(0,0,0,0.1);
  color: #000;
  border-color: rgba(0,0,0,0.2);
}

.btn-delete {
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 6px;
  border-radius: 4px;
  transition: all 0.2s;
  display: flex;
}

.btn-delete:hover {
  color: var(--danger);
  background: rgba(255, 0, 85, 0.1);
  box-shadow: 0 0 10px var(--danger-glow);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  text-align: left;
  font-family: "Rajdhani", sans-serif;
  font-size: 0.85rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--primary);
  padding: 10px 12px;
  border-bottom: 2px solid var(--border);
}

.data-table td {
  padding: 12px;
  font-size: 0.95rem;
  border-bottom: 1px solid var(--border);
}

.data-table tr:last-child td {
  border-bottom: none;
}

.muted {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-left: 8px;
}
</style>
