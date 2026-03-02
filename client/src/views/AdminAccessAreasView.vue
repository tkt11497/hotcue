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

interface AreaRow {
  id: string;
  name: string;
}

const areas = ref<AreaRow[]>([]);
const loading = ref(true);
const newName = ref("");

onMounted(async () => {
  await loadAreas();
});

async function loadAreas() {
  loading.value = true;
  try {
    const snap = await getDocs(collection(db, "accessAreas"));
    areas.value = snap.docs.map((d) => ({
      id: d.id,
      name: d.data().name || d.id,
    }));
  } finally {
    loading.value = false;
  }
}

async function createArea() {
  if (!newName.value.trim()) return;
  await addDoc(collection(db, "accessAreas"), {
    name: newName.value.trim(),
    createdBy: userProfile.value?.uid,
    createdAt: serverTimestamp(),
  });
  newName.value = "";
  await loadAreas();
}

async function deleteArea(id: string) {
  if (!confirm("Delete this access area?")) return;
  await deleteDoc(doc(db, "accessAreas", id));
  await loadAreas();
}
</script>

<template>
  <div class="admin-access-areas">
    <h2>Access Areas</h2>
    <p class="desc">Create and manage access areas (e.g., Zone A, Media Room, VIP Lounge).</p>

    <form @submit.prevent="createArea" class="create-form">
      <input v-model="newName" placeholder="New access area name" class="input" />
      <button type="submit" class="btn-sm" :disabled="!newName.trim()">Create</button>
    </form>

    <div v-if="loading" class="loading">Loading...</div>
    <div v-else-if="areas.length === 0" class="empty">No access areas yet.</div>
    <div v-else class="area-list">
      <div v-for="area in areas" :key="area.id" class="area-row">
        <span class="area-name">{{ area.name }}</span>
        <button class="btn-delete" @click="deleteArea(area.id)" title="Delete access area">
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
.admin-access-areas {
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

.area-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.area-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  transition: all 0.2s;
}

.area-row:hover {
  background: var(--surface-hover);
}

.area-name {
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
