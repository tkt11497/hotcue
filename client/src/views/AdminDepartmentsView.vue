<script setup lang="ts">
import { ref, onMounted } from "vue";
import { db } from "../firebase";
import {
  collection,
  getDocs,
  addDoc,
  deleteDoc,
  doc,
  serverTimestamp,
} from "firebase/firestore";
import { useAuth } from "../composables/useAuth";

const { userProfile } = useAuth();

interface DeptRow {
  id: string;
  name: string;
}

const departments = ref<DeptRow[]>([]);
const loading = ref(true);
const newName = ref("");

onMounted(async () => {
  await loadDepartments();
});

async function loadDepartments() {
  loading.value = true;
  try {
    const snap = await getDocs(collection(db, "departments"));
    departments.value = snap.docs.map((d) => ({
      id: d.id,
      name: d.data().name || d.id,
    }));
  } finally {
    loading.value = false;
  }
}

async function createDepartment() {
  if (!newName.value.trim()) return;
  await addDoc(collection(db, "departments"), {
    name: newName.value.trim(),
    createdBy: userProfile.value?.uid,
    createdAt: serverTimestamp(),
  });
  newName.value = "";
  await loadDepartments();
}

async function deleteDepartment(id: string) {
  if (!confirm("Delete this department?")) return;
  await deleteDoc(doc(db, "departments", id));
  await loadDepartments();
}
</script>

<template>
  <div class="admin-departments">
    <h2>Departments</h2>
    <p class="desc">Create and manage departments for user profiles.</p>

    <form @submit.prevent="createDepartment" class="create-form">
      <input v-model="newName" placeholder="New department name" class="input" />
      <button type="submit" class="btn-sm" :disabled="!newName.trim()">Create</button>
    </form>

    <div v-if="loading" class="loading">Loading...</div>
    <div v-else-if="departments.length === 0" class="empty">No departments yet.</div>
    <div v-else class="dept-list">
      <div v-for="dept in departments" :key="dept.id" class="dept-row">
        <span class="dept-name">{{ dept.name }}</span>
        <button class="btn-delete" @click="deleteDepartment(dept.id)" title="Delete department">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-departments {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
  max-width: 500px;
}

h2 {
  font-size: 1.2rem;
  margin-bottom: 4px;
}

.desc {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-bottom: 16px;
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

.dept-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dept-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  transition: all 0.2s;
}

.dept-row:hover {
  background: var(--surface-hover);
}

.dept-name {
  font-weight: 600;
  font-size: 0.9rem;
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
</style>
