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
  max-width: 600px;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.4);
}

@media (max-width: 768px) {
  .admin-access-areas {
    padding: 16px;
    max-width: 100%;
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

.desc {
  color: var(--text-muted);
  font-size: 0.95rem;
  margin-bottom: 20px;
}

.create-form {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

@media (max-width: 480px) {
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
  font-size: 1rem;
  color: var(--text);
  outline: none;
  transition: all 0.2s;
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
  padding: 10px 20px;
  font-family: "Rajdhani", sans-serif;
  font-size: 1rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-sm:hover:not(:disabled) {
  background: #00e67a;
  box-shadow: 0 0 15px var(--primary-glow);
  transform: translateY(-2px);
}

.btn-sm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--surface);
  color: var(--text-muted);
  border-color: var(--border);
  box-shadow: none;
  transform: none;
}

.loading,
.empty {
  color: var(--text-muted);
  font-family: "Rajdhani", sans-serif;
  font-size: 1.1rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  padding: 24px 0;
  text-align: center;
}

.area-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.area-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  transition: all 0.2s;
}

.area-row:hover {
  background: rgba(0, 255, 136, 0.05);
  border-color: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
  transform: translateY(-2px);
}

.area-name {
  font-family: "Rajdhani", sans-serif;
  font-weight: 700;
  font-size: 1.15rem;
  text-transform: uppercase;
  letter-spacing: 0.5px;
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
</style>
