<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { db, storage } from "../firebase";
import { collection, getDocs, doc, updateDoc } from "firebase/firestore";
import { ref as storageRef, uploadBytes, getDownloadURL } from "firebase/storage";
import { useAuth, type UserRole } from "../composables/useAuth";

const router = useRouter();
const { isAdmin, createUser } = useAuth();

interface AreaOption { id: string; name: string }
interface RoomOption { id: string; name: string }
interface DeptOption { id: string; name: string }

interface UserRow {
  uid: string;
  email: string;
  displayName: string;
  role: string;
  department?: string;
  assignedRoom?: string;
  company?: string;
  accessAreas?: string[];
  photoURL?: string;
}

const users = ref<UserRow[]>([]);
const rooms = ref<RoomOption[]>([]);
const departments = ref<DeptOption[]>([]);
const accessAreas = ref<AreaOption[]>([]);
const loading = ref(true);

const showCreateForm = ref(false);
const newEmail = ref("");
const newPassword = ref("");
const newDisplayName = ref("");
const newRole = ref<UserRole>("member");
const newDepartment = ref("");
const newAssignedRoom = ref("");
const newCompany = ref("");
const newAccessAreas = ref<string[]>([]);
const newPhotoFile = ref<File | null>(null);
const newPhotoPreview = ref<string | null>(null);
const createError = ref<string | null>(null);
const creating = ref(false);

onMounted(async () => {
  try {
    const [userSnap, roomSnap, deptSnap, areaSnap] = await Promise.all([
      getDocs(collection(db, "users")),
      getDocs(collection(db, "rooms")),
      getDocs(collection(db, "departments")),
      getDocs(collection(db, "accessAreas")),
    ]);
    users.value = userSnap.docs.map((d) => {
      const data = d.data();
      return {
        uid: d.id,
        email: data.email,
        displayName: data.displayName,
        role: data.role || "member",
        department: data.department || "",
        assignedRoom: data.assignedRoom || "",
        company: data.company || "",
        accessAreas: data.accessAreas || [],
        photoURL: data.photoURL || "",
      };
    });
    rooms.value = roomSnap.docs.map((d) => ({ id: d.id, name: d.data().name || d.id }));
    departments.value = deptSnap.docs.map((d) => ({ id: d.id, name: d.data().name || d.id }));
    accessAreas.value = areaSnap.docs.map((d) => ({ id: d.id, name: d.data().name || d.id }));
  } finally {
    loading.value = false;
  }
});

function onPhotoSelected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  newPhotoFile.value = file;
  newPhotoPreview.value = URL.createObjectURL(file);
}

function clearPhoto() {
  newPhotoFile.value = null;
  newPhotoPreview.value = null;
}

async function uploadPhoto(uid: string, file: File): Promise<string> {
  const ext = file.name.split(".").pop() || "jpg";
  const fileRef = storageRef(storage, `user-photos/${uid}.${ext}`);
  await uploadBytes(fileRef, file);
  return getDownloadURL(fileRef);
}

async function handleCreateUser() {
  if (!newEmail.value.trim() || !newPassword.value || !newDisplayName.value.trim()) return;
  try {
    createError.value = null;
    creating.value = true;

    const uid = await createUser({
      email: newEmail.value.trim(),
      password: newPassword.value,
      displayName: newDisplayName.value.trim(),
      role: newRole.value,
      department: newDepartment.value || undefined,
      assignedRoom: newAssignedRoom.value || undefined,
      company: newCompany.value.trim() || undefined,
      accessAreas: newAccessAreas.value.length ? newAccessAreas.value : undefined,
    });

    let photoURL = "";
    if (newPhotoFile.value && uid) {
      photoURL = await uploadPhoto(uid, newPhotoFile.value);
      await updateDoc(doc(db, "users", uid), { photoURL });
    }

    users.value.push({
      uid: uid || "(refresh)",
      email: newEmail.value.trim(),
      displayName: newDisplayName.value.trim(),
      role: newRole.value,
      department: newDepartment.value,
      assignedRoom: newAssignedRoom.value,
      company: newCompany.value.trim(),
      accessAreas: [...newAccessAreas.value],
      photoURL,
    });

    newEmail.value = "";
    newPassword.value = "";
    newDisplayName.value = "";
    newRole.value = "member";
    newDepartment.value = "";
    newAssignedRoom.value = "";
    newCompany.value = "";
    newAccessAreas.value = [];
    clearPhoto();
    showCreateForm.value = false;
  } catch (err: any) {
    createError.value = err.message || "Failed to create user";
  } finally {
    creating.value = false;
  }
}

function toggleArea(areaId: string) {
  const idx = newAccessAreas.value.indexOf(areaId);
  if (idx >= 0) newAccessAreas.value.splice(idx, 1);
  else newAccessAreas.value.push(areaId);
}

function getAreaNames(ids: string[]) {
  if (!ids?.length) return "—";
  return ids.map((id) => accessAreas.value.find((a) => a.id === id)?.name || id).join(", ");
}

function getDeptName(id: string) {
  return departments.value.find((d) => d.id === id)?.name || id;
}

function goToUser(uid: string) {
  router.push(`/admin/users/${uid}`);
}

const roleNeedsRoom = (role: string) => role === "room_admin" || role === "member";
</script>

<template>
  <div class="admin-users">
    <div class="section-header">
      <div>
        <h2>Users</h2>
        <p class="desc">Manage user accounts and roles. Click a row to view full profile.</p>
      </div>
      <button v-if="isAdmin" class="btn-sm" @click="showCreateForm = !showCreateForm">
        {{ showCreateForm ? "Cancel" : "+ Create User" }}
      </button>
    </div>

    <div v-if="showCreateForm" class="create-panel">
      <h3>Create New User</h3>
      <form @submit.prevent="handleCreateUser" class="create-grid">
        <div class="field">
          <label>Display Name</label>
          <input v-model="newDisplayName" placeholder="Full name" class="input" required />
        </div>
        <div class="field">
          <label>Email</label>
          <input v-model="newEmail" type="email" placeholder="user@example.com" class="input" required />
        </div>
        <div class="field">
          <label>Password</label>
          <input v-model="newPassword" type="password" placeholder="Min 6 characters" class="input" required minlength="6" />
        </div>
        <div class="field">
          <label>Role</label>
          <select v-model="newRole" class="input">
            <option value="member">Member</option>
            <option value="room_admin">Room Admin</option>
            <option value="holding_admin">Holding Admin</option>
            <option value="security">Security</option>
            <option value="security_admin">Security Admin</option>
            <option value="admin">Admin</option>
          </select>
        </div>
        <div class="field">
          <label>Company (optional)</label>
          <input v-model="newCompany" placeholder="Company name" class="input" />
        </div>
        <div class="field">
          <label>Department</label>
          <select v-model="newDepartment" class="input">
            <option value="">None</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </div>
        <div class="field" v-if="roleNeedsRoom(newRole)">
          <label>Assigned Room</label>
          <select v-model="newAssignedRoom" class="input">
            <option value="">None</option>
            <option v-for="r in rooms" :key="r.id" :value="r.id">{{ r.name }}</option>
          </select>
        </div>
        <div class="field">
          <label>Access Areas</label>
          <div class="checkbox-group">
            <label v-for="a in accessAreas" :key="a.id" class="checkbox-label">
              <input
                type="checkbox"
                :checked="newAccessAreas.includes(a.id)"
                @change="toggleArea(a.id)"
              />
              {{ a.name }}
            </label>
            <span v-if="accessAreas.length === 0" class="muted">No access areas defined yet.</span>
          </div>
        </div>
        <div class="field">
          <label>Photo</label>
          <div class="photo-upload">
            <img v-if="newPhotoPreview" :src="newPhotoPreview" class="photo-preview" />
            <div class="photo-btns">
              <label class="btn-upload">
                Choose File
                <input type="file" accept="image/*" @change="onPhotoSelected" hidden />
              </label>
              <label class="btn-upload">
                Take Photo
                <input type="file" accept="image/*" capture="environment" @change="onPhotoSelected" hidden />
              </label>
              <button v-if="newPhotoFile" type="button" class="btn-clear" @click="clearPhoto">Clear</button>
            </div>
          </div>
        </div>
        <div class="form-actions">
          <div v-if="createError" class="error-msg">{{ createError }}</div>
          <button type="submit" class="btn-primary" :disabled="creating">
            {{ creating ? "Creating..." : "Create User" }}
          </button>
        </div>
      </form>
    </div>

    <div v-if="loading" class="loading">Loading users...</div>

    <table v-else class="data-table">
      <thead>
        <tr>
          <th></th>
          <th>Name</th>
          <th>Company</th>
          <th>Department</th>
          <th>Access Areas</th>
          <th>Role</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in users" :key="u.uid" class="clickable-row" @click="goToUser(u.uid)">
          <td class="avatar-cell">
            <img v-if="u.photoURL" :src="u.photoURL" class="avatar" />
            <div v-else class="avatar-placeholder">{{ u.displayName.charAt(0).toUpperCase() }}</div>
          </td>
          <td>
            <div class="user-name">{{ u.displayName }}</div>
            <div class="muted">{{ u.email }}</div>
          </td>
          <td><span class="muted">{{ u.company || "—" }}</span></td>
          <td><span class="muted">{{ u.department ? getDeptName(u.department) : "—" }}</span></td>
          <td><span class="muted area-list">{{ getAreaNames(u.accessAreas || []) }}</span></td>
          <td>
            <span class="role-tag" :class="u.role">{{ u.role.replace("_", " ") }}</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.admin-users {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}

h2 { font-size: 1.2rem; margin-bottom: 4px; }
h3 { font-size: 1rem; margin-bottom: 16px; }

.desc { color: var(--text-muted); font-size: 0.85rem; }

.create-panel {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 20px;
  margin-bottom: 20px;
}

.create-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.form-actions {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.field { display: flex; flex-direction: column; gap: 4px; }

.field label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.input {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 0.9rem;
  color: var(--text);
  outline: none;
  width: 100%;
}

.input:focus { border-color: var(--primary); }
.input::placeholder { color: var(--text-muted); opacity: 0.6; }

.checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 0.85rem;
  cursor: pointer;
  text-transform: none;
  font-weight: 400;
  color: var(--text);
}

.checkbox-label input[type="checkbox"] {
  accent-color: var(--primary);
}

.photo-upload { display: flex; align-items: center; gap: 12px; }

.photo-preview {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-sm);
  object-fit: cover;
  border: 1px solid var(--border);
}

.photo-btns { display: flex; gap: 6px; flex-wrap: wrap; }

.btn-upload {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 6px 12px;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-upload:hover { border-color: var(--primary); color: var(--text); }

.btn-clear {
  background: none;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 6px 12px;
  font-size: 0.8rem;
  color: var(--danger);
  cursor: pointer;
}

.error-msg {
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 0.85rem;
  color: var(--danger);
  width: 100%;
}

.btn-primary {
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 10px 20px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-primary:hover:not(:disabled) { background: var(--primary-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.loading { color: var(--text-muted); padding: 24px 0; text-align: center; }

.data-table { width: 100%; border-collapse: collapse; }

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

.data-table tr:last-child td { border-bottom: none; }

.clickable-row {
  cursor: pointer;
  transition: background 0.15s;
}

.clickable-row:hover {
  background: var(--surface-hover);
}

.avatar-cell { width: 48px; }

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--surface-hover);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-muted);
}

.user-name { font-weight: 600; }

.muted { color: var(--text-muted); font-size: 0.85rem; }

.area-list { font-size: 0.8rem; }

.role-tag {
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 2px 8px;
  border-radius: 10px;
  white-space: nowrap;
}

.role-tag.admin { background: rgba(231, 76, 60, 0.15); color: var(--danger); }
.role-tag.holding_admin { background: rgba(243, 156, 18, 0.15); color: #f39c12; }
.role-tag.room_admin { background: rgba(52, 152, 219, 0.15); color: #3498db; }
.role-tag.member { background: rgba(46, 204, 113, 0.15); color: var(--success); }

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

.btn-sm:hover { background: var(--primary-hover); }
</style>
