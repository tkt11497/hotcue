<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed } from "vue";
import { useAuth } from "../composables/useAuth";
import { Html5Qrcode } from "html5-qrcode";
import { db } from "../firebase";
import { doc, getDoc, collection, getDocs } from "firebase/firestore";
import QRCode from "qrcode";

interface ScannedUser {
  uid: string;
  email: string;
  displayName: string;
  role: string;
  department?: string;
  company?: string;
  accessAreas?: string[];
  accessAreaIds?: string[];
  photoURL?: string;
}

const scanning = ref(false);
const scannedUser = ref<ScannedUser | null>(null);
const qrDataUrl = ref("");
const error = ref("");
const loadingUser = ref(false);

const { userProfile } = useAuth();

const securityHasNoAreas = computed(() => !userProfile.value?.accessAreas?.length);

const accessDenied = computed(() => {
  if (!scannedUser.value || securityHasNoAreas.value) return false;
  const securityAreas = new Set(userProfile.value!.accessAreas!);
  const userAreas = scannedUser.value.accessAreaIds ?? [];
  return !userAreas.some((id) => securityAreas.has(id));
});

let scanner: Html5Qrcode | null = null;

onMounted(() => {
  startScanner();
});

onUnmounted(() => {
  stopScanner();
});

async function startScanner() {
  error.value = "";
  scannedUser.value = null;
  qrDataUrl.value = "";
  scanning.value = true;

  await nextTick();

  try {
    scanner = new Html5Qrcode("qr-reader");
    await scanner.start(
      { facingMode: "environment" },
      { fps: 10, qrbox: { width: 250, height: 250 } },
      onScanSuccess,
      () => {}
    );
  } catch (err: any) {
    error.value = err?.message || "Failed to start camera";
    scanning.value = false;
  }
}

async function stopScanner() {
  if (scanner) {
    try {
      await scanner.stop();
    } catch {}
    scanner = null;
  }
  scanning.value = false;
}

async function onScanSuccess(decodedText: string) {
  await stopScanner();
  await lookupUser(decodedText.trim());
}

async function lookupUser(uid: string) {
  loadingUser.value = true;
  error.value = "";
  scannedUser.value = null;
  qrDataUrl.value = "";

  try {
    const [snap, deptSnap, areaSnap] = await Promise.all([
      getDoc(doc(db, "users", uid)),
      getDocs(collection(db, "departments")),
      getDocs(collection(db, "accessAreas")),
    ]);

    if (!snap.exists()) {
      error.value = "No user found for this QR code.";
      return;
    }

    const data = snap.data();

    const deptMap = new Map(deptSnap.docs.map((d) => [d.id, d.data().name || d.id]));
    const areaMap = new Map(areaSnap.docs.map((d) => [d.id, d.data().name || d.id]));

    const departmentName = data.department ? (deptMap.get(data.department) ?? data.department) : undefined;
    const areaNames = data.accessAreas?.map((id: string) => areaMap.get(id) ?? id);

    scannedUser.value = {
      uid,
      email: data.email,
      displayName: data.displayName,
      role: data.role ?? "member",
      department: departmentName,
      company: data.company,
      accessAreas: areaNames,
      accessAreaIds: data.accessAreas ?? [],
      photoURL: data.photoURL,
    };

    qrDataUrl.value = await QRCode.toDataURL(uid, {
      width: 160,
      margin: 1,
      color: { dark: "#000000", light: "#ffffff" },
    });
  } catch (err: any) {
    error.value = err?.message || "Failed to load user data.";
  } finally {
    loadingUser.value = false;
  }
}

function scanAgain() {
  scannedUser.value = null;
  error.value = "";
  startScanner();
}
</script>

<template>
  <div class="scanner-page">
    <h2>QR Scanner</h2>
    <p class="desc">Scan a member's QR code to view their info and access areas.</p>

    <div v-if="securityHasNoAreas" class="no-area-card">
      <div class="no-area-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 9v4" />
          <path d="M10.363 3.591l-8.106 13.534A1.914 1.914 0 0 0 3.891 20h16.218a1.914 1.914 0 0 0 1.634-2.875L13.637 3.59a1.914 1.914 0 0 0-3.274 0z" />
          <path d="M12 17h.01" />
        </svg>
      </div>
      <h3 class="no-area-title">No Area Assigned</h3>
      <p class="no-area-msg">You are not assigned to any access area. Please ask an admin to assign you to an area before scanning.</p>
    </div>

    <template v-else>
    <div v-if="scanning" class="scanner-container">
      <div id="qr-reader"></div>
    </div>

    <div v-if="error" class="error-msg">
      {{ error }}
      <button class="btn-scan" @click="scanAgain">Try Again</button>
    </div>

    <div v-if="loadingUser" class="loading">Loading member info...</div>

    <div v-if="scannedUser && accessDenied" class="denied-card">
      <div class="denied-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10" />
          <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
        </svg>
      </div>
      <h3 class="denied-title">Not Allowed to Enter</h3>
      <p class="denied-msg">This member does not have access to your assigned area.</p>
    </div>

    <div v-if="scannedUser && !accessDenied" class="allowed-card">
      <div class="allowed-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
          <polyline points="22 4 12 14.01 9 11.01" />
        </svg>
      </div>
      <h3 class="allowed-title">Allowed to Enter</h3>
      <p class="allowed-msg">This member has access to your assigned area.</p>
    </div>

    <div v-if="scannedUser" class="user-card">
      <div class="card-header-row">
        <div v-if="scannedUser.photoURL" class="user-photo">
          <img :src="scannedUser.photoURL" :alt="scannedUser.displayName" />
        </div>
        <div v-else class="user-photo placeholder">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
            <circle cx="12" cy="7" r="4" />
          </svg>
        </div>
        <div class="card-name-section">
          <h3>{{ scannedUser.displayName }}</h3>
          <span class="role-tag" :class="scannedUser.role">{{ scannedUser.role.replace("_", " ") }}</span>
        </div>
      </div>

      <div class="info-grid">
        <div v-if="scannedUser.company" class="info-item">
          <label>Company</label>
          <span>{{ scannedUser.company }}</span>
        </div>
        <div v-if="scannedUser.department" class="info-item">
          <label>Department</label>
          <span>{{ scannedUser.department }}</span>
        </div>
        <div class="info-item">
          <label>Email</label>
          <span>{{ scannedUser.email }}</span>
        </div>
      </div>

      <div v-if="scannedUser.accessAreas?.length" class="access-section">
        <label>Access Areas</label>
        <div class="area-tags">
          <span v-for="area in scannedUser.accessAreas" :key="area" class="area-tag">{{ area }}</span>
        </div>
      </div>
      <div v-else class="access-section">
        <label>Access Areas</label>
        <span class="no-access">No access areas assigned</span>
      </div>

      <div v-if="qrDataUrl" class="qr-section">
        <img :src="qrDataUrl" alt="QR Code" class="qr-img" />
      </div>

      <button class="btn-scan" @click="scanAgain">Scan Another</button>
    </div>
    </template>
  </div>
</template>

<style scoped>
.scanner-page {
  max-width: 500px;
  margin: 0 auto;
  padding: 24px;
}

h2 {
  font-size: 1.3rem;
  margin-bottom: 4px;
}

.desc {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin-bottom: 24px;
}

.scanner-container {
  border-radius: var(--radius);
  overflow: hidden;
  margin-bottom: 20px;
  background: #000;
}

#qr-reader {
  width: 100%;
}

.error-msg {
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  color: #e74c3c;
  padding: 16px;
  border-radius: var(--radius);
  text-align: center;
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.loading {
  text-align: center;
  color: var(--text-muted);
  padding: 24px;
}

.no-area-card {
  background: rgba(243, 156, 18, 0.08);
  border: 2px solid rgba(243, 156, 18, 0.4);
  border-radius: var(--radius);
  padding: 40px 24px;
  text-align: center;
}

.no-area-icon {
  color: #f39c12;
  margin-bottom: 12px;
}

.no-area-title {
  font-size: 1.2rem;
  font-weight: 700;
  color: #f39c12;
  margin: 0 0 8px 0;
}

.no-area-msg {
  color: var(--text-muted);
  font-size: 0.9rem;
  margin: 0;
  line-height: 1.5;
}

.allowed-card {
  background: rgba(46, 204, 113, 0.08);
  border: 2px solid rgba(46, 204, 113, 0.4);
  border-radius: var(--radius);
  padding: 28px 24px;
  text-align: center;
  margin-bottom: 16px;
}

.allowed-icon {
  color: #2ecc71;
  margin-bottom: 12px;
}

.allowed-title {
  font-size: 1.2rem;
  font-weight: 700;
  color: #2ecc71;
  margin: 0 0 6px 0;
}

.allowed-msg {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin: 0;
}

.denied-card {
  background: rgba(231, 76, 60, 0.08);
  border: 2px solid rgba(231, 76, 60, 0.4);
  border-radius: var(--radius);
  padding: 28px 24px;
  text-align: center;
  margin-bottom: 16px;
}

.denied-icon {
  color: #e74c3c;
  margin-bottom: 12px;
}

.denied-title {
  font-size: 1.2rem;
  font-weight: 700;
  color: #e74c3c;
  margin: 0 0 6px 0;
}

.denied-msg {
  color: var(--text-muted);
  font-size: 0.85rem;
  margin: 0;
}

.user-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
}

.card-header-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.user-photo {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  border: 2px solid var(--border);
}

.user-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-photo.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  color: var(--text-muted);
}

.card-name-section h3 {
  font-size: 1.2rem;
  margin: 0 0 4px 0;
}

.role-tag {
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 2px 8px;
  border-radius: 8px;
  display: inline-block;
}

.role-tag.admin { background: rgba(231, 76, 60, 0.15); color: #e74c3c; }
.role-tag.holding_admin { background: rgba(243, 156, 18, 0.15); color: #f39c12; }
.role-tag.room_admin { background: rgba(52, 152, 219, 0.15); color: #3498db; }
.role-tag.security_admin { background: rgba(155, 89, 182, 0.15); color: #9b59b6; }
.role-tag.security { background: rgba(142, 68, 173, 0.15); color: #8e44ad; }
.role-tag.member { background: rgba(46, 204, 113, 0.15); color: #2ecc71; }

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.info-item label {
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
}

.info-item span {
  font-size: 0.95rem;
}

.access-section {
  margin-bottom: 20px;
}

.access-section label {
  display: block;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.area-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.area-tag {
  background: rgba(108, 92, 231, 0.12);
  color: var(--primary);
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.85rem;
  font-weight: 600;
}

.no-access {
  color: var(--text-muted);
  font-size: 0.85rem;
  font-style: italic;
}

.qr-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 20px;
  padding: 16px;
  background: var(--bg);
  border-radius: var(--radius);
}

.qr-img {
  width: 120px;
  height: 120px;
}

.btn-scan {
  width: 100%;
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 12px;
  font-size: 0.95rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-scan:hover {
  background: var(--primary-hover);
}
</style>
