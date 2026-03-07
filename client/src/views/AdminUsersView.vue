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

function getRoomName(id: string) {
  return rooms.value.find((r) => r.id === id)?.name || id;
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

    <div v-else class="table-responsive">
      <table class="data-table">
        <thead>
          <tr>
            <th></th>
            <th>Name</th>
            <th>Company</th>
            <th>Department</th>
            <th>Assigned Room</th>
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
            <td><span class="muted">{{ u.assignedRoom ? getRoomName(u.assignedRoom) : "—" }}</span></td>
            <td><span class="muted area-list">{{ getAreaNames(u.accessAreas || []) }}</span></td>
            <td>
              <span class="role-tag" :class="u.role">{{ u.role.replace("_", " ") }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.admin-users {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.4);
}

@media (max-width: 768px) {
  .admin-users {
    padding: 16px;
  }
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 12px;
}

h2 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1.6rem;
  margin-bottom: 4px;
  color: var(--primary);
  text-shadow: 0 0 10px var(--primary-glow);
}

h3 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1.2rem;
  margin-bottom: 16px;
  color: var(--text);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.desc { color: var(--text-muted); font-size: 0.9rem; }

.create-panel {
  background: var(--bg);
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  padding: 20px;
  margin-bottom: 20px;
  box-shadow: 0 0 15px rgba(0, 255, 136, 0.1);
}

.create-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 768px) {
  .create-grid {
    grid-template-columns: 1fr;
  }
}

.form-actions {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  margin-top: 8px;
}

.field { display: flex; flex-direction: column; gap: 6px; }

.field label {
  font-family: "Rajdhani", sans-serif;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.input {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  font-family: "Outfit", sans-serif;
  font-size: 0.95rem;
  color: var(--text);
  outline: none;
  width: 100%;
  transition: all 0.2s;
}

.input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.input::placeholder { color: var(--text-muted); opacity: 0.5; }

.checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.9rem;
  cursor: pointer;
  text-transform: none;
  font-weight: 400;
  color: var(--text);
}

.checkbox-label input[type="checkbox"] {
  accent-color: var(--primary);
  width: 16px;
  height: 16px;
}

.photo-upload { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }

.photo-preview {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-sm);
  object-fit: cover;
  border: 1px solid var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.photo-btns { display: flex; gap: 8px; flex-wrap: wrap; }

.btn-upload {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-family: "Rajdhani", sans-serif;
  font-size: 0.9rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-upload:hover {
  border-color: var(--primary);
  color: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.btn-clear {
  background: none;
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  font-family: "Rajdhani", sans-serif;
  font-size: 0.9rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  color: var(--danger);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-clear:hover {
  background: rgba(255, 0, 85, 0.1);
  box-shadow: 0 0 10px var(--danger-glow);
}

.error-msg {
  background: rgba(255, 0, 85, 0.1);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.9rem;
  color: var(--danger);
  width: 100%;
  box-shadow: inset 0 0 10px rgba(255, 0, 85, 0.2);
}

.btn-primary {
  background: var(--primary);
  color: #000;
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  padding: 10px 20px;
  font-family: "Rajdhani", sans-serif;
  font-size: 1rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 0 15px var(--primary-glow);
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
  transform: translateY(-2px);
  box-shadow: 0 0 20px var(--primary-glow);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  box-shadow: none;
}

.loading { color: var(--text-muted); padding: 24px 0; text-align: center; font-family: "Rajdhani", sans-serif; text-transform: uppercase; letter-spacing: 1px; }

.table-responsive {
  width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.data-table { width: 100%; border-collapse: collapse; min-width: 700px; }

.data-table th {
  text-align: left;
  font-family: "Rajdhani", sans-serif;
  font-size: 0.9rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--primary);
  padding: 12px 16px;
  border-bottom: 2px solid var(--border);
}

.data-table td {
  padding: 12px 16px;
  font-size: 0.95rem;
  border-bottom: 1px solid var(--border);
}

.data-table tr:last-child td { border-bottom: none; }

.clickable-row {
  cursor: pointer;
  transition: all 0.2s;
}

.clickable-row:hover {
  background: rgba(0, 255, 136, 0.05);
}

.avatar-cell { width: 56px; }

.avatar {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid var(--primary);
}

.avatar-placeholder {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: var(--bg);
  border: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: "Rajdhani", sans-serif;
  font-size: 1.2rem;
  font-weight: 700;
  color: var(--text-muted);
}

.user-name { font-weight: 600; font-family: "Rajdhani", sans-serif; font-size: 1.1rem; text-transform: uppercase; letter-spacing: 0.5px; }

.muted { color: var(--text-muted); font-size: 0.85rem; }

.area-list { font-size: 0.85rem; }

.role-tag {
  font-family: "Rajdhani", sans-serif;
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  white-space: nowrap;
  display: inline-block;
  border: 1px solid transparent;
}

.role-tag.admin { background: rgba(255, 0, 85, 0.15); color: var(--danger); border-color: rgba(255, 0, 85, 0.3); }
.role-tag.holding_admin { background: rgba(243, 156, 18, 0.15); color: #f39c12; border-color: rgba(243, 156, 18, 0.3); }
.role-tag.room_admin { background: rgba(52, 152, 219, 0.15); color: #3498db; border-color: rgba(52, 152, 219, 0.3); }
.role-tag.member { background: rgba(0, 255, 136, 0.15); color: var(--success); border-color: rgba(0, 255, 136, 0.3); }
.role-tag.security, .role-tag.security_admin { background: rgba(155, 89, 182, 0.15); color: #9b59b6; border-color: rgba(155, 89, 182, 0.3); }

.btn-sm {
  background: var(--bg);
  color: var(--primary);
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-family: "Rajdhani", sans-serif;
  font-size: 0.95rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-sm:hover {
  background: rgba(0, 255, 136, 0.1);
  box-shadow: 0 0 10px var(--primary-glow);
  transform: translateY(-1px);
}
</style>
