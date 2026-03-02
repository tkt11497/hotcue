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
}

h2 {
  font-size: 1.2rem;
  margin-bottom: 4px;
}

h3 {
  font-size: 1rem;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.desc {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-bottom: 16px;
}

.hint {
  color: var(--text-muted);
  font-size: 0.8rem;
  margin-bottom: 16px;
  font-style: italic;
}

.create-form {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.input {
  flex: 1;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 0.9rem;
  color: var(--text);
  outline: none;
}

.type-select {
  flex: 0 0 auto;
  width: 150px;
}

.input:focus {
  border-color: var(--primary);
}

.input::placeholder {
  color: var(--text-muted);
  opacity: 0.6;
}

.btn-sm {
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
}

.btn-sm:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn-sm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading,
.empty {
  color: var(--text-muted);
  font-size: 0.85rem;
  padding: 16px 0;
  text-align: center;
}

.room-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.room-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s;
}

.room-row:hover {
  background: var(--surface-hover);
}

.room-row.active {
  background: rgba(108, 92, 231, 0.1);
  color: var(--primary);
}

.room-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.room-name {
  font-weight: 600;
  font-size: 0.9rem;
}

.holding-badge {
  font-size: 0.6rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(243, 156, 18, 0.15);
  color: #f39c12;
}

.security-badge {
  font-size: 0.6rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(155, 89, 182, 0.15);
  color: #9b59b6;
}

.btn-delete {
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: all 0.2s;
  display: flex;
}

.btn-delete:hover {
  color: var(--danger);
  background: rgba(231, 76, 60, 0.1);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  text-align: left;
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
}

.data-table td {
  padding: 10px 12px;
  font-size: 0.9rem;
  border-bottom: 1px solid var(--border);
}

.data-table tr:last-child td {
  border-bottom: none;
}

.muted {
  color: var(--text-muted);
  font-size: 0.8rem;
  margin-left: 6px;
}
</style>
