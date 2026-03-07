<script setup lang="ts">
import { ref, onMounted, watch, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { db, storage } from "../firebase";
import { doc, getDoc, updateDoc, deleteDoc, collection, getDocs } from "firebase/firestore";
import { ref as storageRef, uploadBytes, getDownloadURL } from "firebase/storage";
import QRCode from "qrcode";

const route = useRoute();
const router = useRouter();
const uid = computed(() => route.params.uid as string);

interface UserData {
  email: string;
  displayName: string;
  role: string;
  department?: string;
  assignedRoom?: string;
  company?: string;
  accessAreas?: string[];
  photoURL?: string;
}

interface OptionItem { id: string; name: string }

const userData = ref<UserData | null>(null);
const loading = ref(true);
const saving = ref(false);
const qrDataUrl = ref("");

const departments = ref<OptionItem[]>([]);
const rooms = ref<OptionItem[]>([]);
const accessAreas = ref<OptionItem[]>([]);

const editName = ref("");
const editCompany = ref("");
const editDepartment = ref("");
const editAssignedRoom = ref("");
const editRole = ref("");
const editAccessAreas = ref<string[]>([]);

onMounted(() => loadUser());

watch(uid, () => loadUser());

async function loadUser() {
  loading.value = true;
  try {
    const [userSnap, deptSnap, roomSnap, areaSnap] = await Promise.all([
      getDoc(doc(db, "users", uid.value)),
      getDocs(collection(db, "departments")),
      getDocs(collection(db, "rooms")),
      getDocs(collection(db, "accessAreas")),
    ]);

    if (!userSnap.exists()) {
      router.push("/admin/users");
      return;
    }

    const data = userSnap.data() as UserData;
    userData.value = data;

    editName.value = data.displayName || "";
    editCompany.value = data.company || "";
    editDepartment.value = data.department || "";
    editAssignedRoom.value = data.assignedRoom || "";
    editRole.value = data.role || "member";
    editAccessAreas.value = data.accessAreas ? [...data.accessAreas] : [];

    departments.value = deptSnap.docs.map((d) => ({ id: d.id, name: d.data().name || d.id }));
    rooms.value = roomSnap.docs.map((d) => ({ id: d.id, name: d.data().name || d.id }));
    accessAreas.value = areaSnap.docs.map((d) => ({ id: d.id, name: d.data().name || d.id }));

    qrDataUrl.value = await QRCode.toDataURL(uid.value, {
      width: 200,
      margin: 1,
      color: { dark: "#000000", light: "#ffffff" },
    });
  } finally {
    loading.value = false;
  }
}

async function saveProfile() {
  saving.value = true;
  try {
    const updates: Record<string, any> = {
      displayName: editName.value.trim(),
      company: editCompany.value.trim(),
      department: editDepartment.value,
      assignedRoom: editAssignedRoom.value,
      role: editRole.value,
      accessAreas: editAccessAreas.value,
    };
    await updateDoc(doc(db, "users", uid.value), updates);
    if (userData.value) {
      userData.value = { ...userData.value, ...updates };
    }
  } finally {
    saving.value = false;
  }
}

async function onPhotoChange(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  const ext = file.name.split(".").pop() || "jpg";
  const fileRef = storageRef(storage, `user-photos/${uid.value}.${ext}`);
  await uploadBytes(fileRef, file);
  const url = await getDownloadURL(fileRef);
  await updateDoc(doc(db, "users", uid.value), { photoURL: url });
  if (userData.value) userData.value.photoURL = url;
}

function toggleArea(areaId: string) {
  const idx = editAccessAreas.value.indexOf(areaId);
  if (idx >= 0) editAccessAreas.value.splice(idx, 1);
  else editAccessAreas.value.push(areaId);
}

function getAreaName(id: string) {
  return accessAreas.value.find((a) => a.id === id)?.name || id;
}

function getDeptName(id: string) {
  return departments.value.find((d) => d.id === id)?.name || id;
}

function printCard() {
  window.print();
}

async function removeUser() {
  if (!confirm(`Delete user "${userData.value?.displayName}"? This removes the Firestore profile. The Firebase Auth account must be removed separately from the Firebase console.`)) return;
  await deleteDoc(doc(db, "users", uid.value));
  router.push("/admin/users");
}

const roleNeedsRoom = (role: string) => role === "room_admin" || role === "member";
</script>

<template>
  <div v-if="loading" class="loading">Loading user profile...</div>

  <div v-else-if="userData" class="user-detail no-print">
    <button class="back-btn" @click="router.push('/admin/users')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="15 18 9 12 15 6" />
      </svg>
      Back to Users
    </button>

    <div class="profile-layout">
      <div class="photo-section">
        <div class="photo-container">
          <img v-if="userData.photoURL" :src="userData.photoURL" class="profile-photo" />
          <div v-else class="photo-placeholder">
            {{ userData.displayName.charAt(0).toUpperCase() }}
          </div>
        </div>
        <label class="btn-change-photo">
          Change Photo
          <input type="file" accept="image/*" @change="onPhotoChange" hidden />
        </label>
        <label class="btn-change-photo">
          Take Photo
          <input type="file" accept="image/*" capture="environment" @change="onPhotoChange" hidden />
        </label>

        <div class="qr-section">
          <img v-if="qrDataUrl" :src="qrDataUrl" class="qr-code" />
          <p class="qr-label">{{ uid }}</p>
        </div>

        <button class="btn-print" @click="printCard">Print Profile Card</button>
        <button class="btn-danger" @click="removeUser">Remove User</button>
      </div>

      <div class="info-section">
        <h2>{{ userData.displayName }}</h2>
        <p class="email">{{ userData.email }}</p>
        <span class="role-tag" :class="userData.role">{{ userData.role.replace("_", " ") }}</span>

        <form @submit.prevent="saveProfile" class="edit-form">
          <div class="field">
            <label>Display Name</label>
            <input v-model="editName" class="input" required />
          </div>
          <div class="field">
            <label>Company</label>
            <input v-model="editCompany" class="input" placeholder="Company name" />
          </div>
          <div class="field">
            <label>Role</label>
            <select v-model="editRole" class="input">
              <option value="member">Member</option>
              <option value="room_admin">Room Admin</option>
              <option value="holding_admin">Holding Admin</option>
              <option value="security">Security</option>
              <option value="security_admin">Security Admin</option>
              <option value="admin">Admin</option>
            </select>
          </div>
          <div class="field">
            <label>Department</label>
            <select v-model="editDepartment" class="input">
              <option value="">None</option>
              <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </div>
          <div class="field" v-if="roleNeedsRoom(editRole)">
            <label>Assigned Room</label>
            <select v-model="editAssignedRoom" class="input">
              <option value="">None</option>
              <option v-for="r in rooms" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select>
          </div>
          <div class="field full-width">
            <label>Access Areas</label>
            <div class="checkbox-group">
              <label v-for="a in accessAreas" :key="a.id" class="checkbox-label">
                <input
                  type="checkbox"
                  :checked="editAccessAreas.includes(a.id)"
                  @change="toggleArea(a.id)"
                />
                {{ a.name }}
              </label>
              <span v-if="accessAreas.length === 0" class="muted">No access areas defined.</span>
            </div>
          </div>
          <div class="form-actions full-width">
            <button type="submit" class="btn-primary" :disabled="saving">
              {{ saving ? "Saving..." : "Save Changes" }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>

  <!-- Print-only card layout -->
  <div v-if="userData" class="print-card">
    <div class="card">
      <div class="card-header">
        <h1>Hot Cue</h1>
      </div>
      <div class="card-body">
        <div class="card-photo-col">
          <img v-if="userData.photoURL" :src="userData.photoURL" class="card-photo" />
          <div v-else class="card-photo-empty">{{ userData.displayName.charAt(0).toUpperCase() }}</div>
        </div>
        <div class="card-info-col">
          <div class="card-name">{{ userData.displayName }}</div>
          <div v-if="userData.company" class="card-company">{{ userData.company }}</div>
          <div v-if="userData.department" class="card-dept">{{ getDeptName(userData.department) }}</div>
          <div v-if="editAccessAreas.length" class="card-areas">
            <span v-for="id in editAccessAreas" :key="id" class="card-area-tag">{{ getAreaName(id) }}</span>
          </div>
        </div>
        <div class="card-qr-col">
          <img v-if="qrDataUrl" :src="qrDataUrl" class="card-qr" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.loading {
  color: var(--text-muted);
  padding: 48px;
  text-align: center;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  padding: 6px 0;
  margin-bottom: 20px;
  transition: color 0.2s;
}

.back-btn:hover { color: var(--primary); }

.user-detail {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
  max-width: 900px;
}

.profile-layout {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}

.photo-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  min-width: 200px;
}

.photo-container { position: relative; }

.profile-photo {
  width: 160px;
  height: 160px;
  border-radius: var(--radius);
  object-fit: cover;
  border: 2px solid var(--border);
}

.photo-placeholder {
  width: 160px;
  height: 160px;
  border-radius: var(--radius);
  background: var(--surface-hover);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 3rem;
  font-weight: 700;
  color: var(--text-muted);
}

.btn-change-photo {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 6px 14px;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
  width: 100%;
}

.btn-change-photo:hover { border-color: var(--primary); color: var(--text); }

.qr-section {
  margin-top: 8px;
  text-align: center;
}

.qr-code {
  width: 140px;
  height: 140px;
  border-radius: var(--radius-sm);
}

.qr-label {
  font-size: 0.65rem;
  color: var(--text-muted);
  word-break: break-all;
  margin-top: 4px;
  max-width: 160px;
}

.btn-print {
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 10px 20px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
  width: 100%;
  margin-top: 4px;
}

.btn-print:hover { background: var(--primary-hover); }

.btn-danger {
  background: none;
  color: var(--danger);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  padding: 10px 20px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  width: 100%;
}

.btn-danger:hover {
  background: var(--danger);
  color: white;
}

.info-section { flex: 1; min-width: 300px; }

.info-section h2 { font-size: 1.4rem; margin-bottom: 4px; }

.email { color: var(--text-muted); font-size: 0.9rem; margin-bottom: 8px; }

.role-tag {
  display: inline-block;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 2px 8px;
  border-radius: 10px;
  margin-bottom: 20px;
}

.role-tag.admin { background: rgba(231, 76, 60, 0.15); color: #e74c3c; }
.role-tag.holding_admin { background: rgba(243, 156, 18, 0.15); color: #f39c12; }
.role-tag.room_admin { background: rgba(52, 152, 219, 0.15); color: #3498db; }
.role-tag.member { background: rgba(46, 204, 113, 0.15); color: #2ecc71; }

.edit-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.full-width { grid-column: 1 / -1; }

.field { display: flex; flex-direction: column; gap: 4px; }

.field label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.input {
  background: var(--bg);
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

.checkbox-group { display: flex; flex-wrap: wrap; gap: 10px; }

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

.checkbox-label input[type="checkbox"] { accent-color: var(--primary); }

.muted { color: var(--text-muted); font-size: 0.85rem; }

.form-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.btn-primary {
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 10px 24px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-primary:hover:not(:disabled) { background: var(--primary-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

/* ---- Print-only card ---- */
.print-card { display: none; }
</style>

<style>
@media print {
  .app-header,
  .admin-sidebar,
  .no-print {
    display: none !important;
  }

  .app-main {
    padding: 0 !important;
  }

  .admin-layout {
    display: block !important;
  }

  .admin-content {
    width: 100% !important;
  }

  .print-card {
    display: block !important;
    padding: 0;
    margin: 0;
  }

  .card {
    width: 86mm;
    height: 54mm;
    border: 1px solid #ccc;
    border-radius: 4px;
    overflow: hidden;
    font-family: "Inter", Arial, sans-serif;
    color: #000;
    background: #fff;
    display: flex;
    flex-direction: column;
  }

  .card-header {
    background: #6c5ce7;
    color: #fff;
    padding: 4px 10px;
    font-size: 10pt;
  }

  .card-header h1 {
    margin: 0;
    font-size: 10pt;
    font-weight: 700;
  }

  .card-body {
    display: flex;
    flex: 1;
    padding: 6px 10px;
    gap: 8px;
    align-items: center;
  }

  .card-photo-col { flex-shrink: 0; }

  .card-photo {
    width: 28mm;
    height: 28mm;
    object-fit: cover;
    border-radius: 3px;
  }

  .card-photo-empty {
    width: 28mm;
    height: 28mm;
    background: #eee;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 18pt;
    font-weight: 700;
    color: #999;
    border-radius: 3px;
  }

  .card-info-col {
    flex: 1;
    min-width: 0;
  }

  .card-name {
    font-size: 10pt;
    font-weight: 700;
    margin-bottom: 2px;
  }

  .card-company {
    font-size: 7pt;
    color: #555;
    margin-bottom: 1px;
  }

  .card-dept {
    font-size: 7pt;
    color: #555;
    margin-bottom: 3px;
  }

  .card-areas { display: flex; flex-wrap: wrap; gap: 2px; }

  .card-area-tag {
    font-size: 6pt;
    background: #eee;
    padding: 1px 4px;
    border-radius: 3px;
    color: #333;
  }

  .card-qr-col { flex-shrink: 0; }

  .card-qr {
    width: 22mm;
    height: 22mm;
  }

  @page {
    size: 86mm 54mm;
    margin: 0;
  }
}
</style>
