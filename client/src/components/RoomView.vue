<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from "vue";
import type { RoomUser } from "../composables/useSignaling";
import type { PeerState } from "../composables/useWebRTC";
import AudioPeer from "./AudioPeer.vue";
import DebugPanel from "./DebugPanel.vue";

const props = defineProps<{
  roomId: string;
  roomName: string;
  joiningPhase: boolean;
  callPhase: string;
  recoveryState: string;
  users: readonly RoomUser[];
  myId: string;
  peerStates: Map<string, PeerState>;
  isMuted: boolean;
  isSpeakerOn: boolean;
  socketConnected: boolean;
  socketId: string | null;
  micStream: MediaStream | null;
  peerConnectionStates: Map<string, { connectionState: string; iceState: string }>;
  latency: { rtt: number | null; jitter: number | null; packetsLost: number };
}>();

const emit = defineEmits<{
  "toggle-mute": [];
  "toggle-speaker": [];
  leave: [];
}>();

const otherUsers = computed(() => props.users.filter((u) => u.id !== props.myId));
const myUser = computed(() => props.users.find((u) => u.id === props.myId));

const rttClass = computed(() => {
  const rtt = props.latency.rtt;
  if (rtt === null) return "neutral";
  if (rtt < 50) return "good";
  if (rtt < 150) return "ok";
  return "bad";
});

const networkOnline = ref(navigator.onLine);
function onOnline() { networkOnline.value = true; }
function onOffline() { networkOnline.value = false; }
onMounted(() => {
  window.addEventListener("online", onOnline);
  window.addEventListener("offline", onOffline);
});
onUnmounted(() => {
  window.removeEventListener("online", onOnline);
  window.removeEventListener("offline", onOffline);
});

const initialConnectDone = ref(false);
let joinPhaseTimeout: ReturnType<typeof setTimeout> | null = null;

const allConnected = computed(() => {
  if (otherUsers.value.length === 0) return true;
  for (const user of otherUsers.value) {
    const pc = props.peerConnectionStates.get(user.id);
    if (!pc || pc.connectionState !== "connected") return false;
  }
  return true;
});

const showConnectingScreen = computed(() => {
  if (!props.joiningPhase) return false;
  if (initialConnectDone.value) return false;
  if (otherUsers.value.length === 0) return false;
  return ["joining_room", "signaling_ready", "rtc_connecting"].includes(props.callPhase);
});

const hasDisconnectedPeer = computed(() => {
  if (props.callPhase === "degraded" || props.callPhase === "failed") return true;
  for (const [, pc] of props.peerConnectionStates) {
    if (pc.connectionState === "disconnected" || pc.connectionState === "failed") return true;
  }
  return false;
});

watch(allConnected, (val) => {
  if (val && !initialConnectDone.value) {
    initialConnectDone.value = true;
  }
});

watch(
  () => [props.joiningPhase, props.callPhase, allConnected.value] as const,
  ([joiningPhase, callPhase, everyoneConnected]) => {
    if (!joiningPhase) return;
    if (everyoneConnected || callPhase === "connected" || callPhase === "degraded" || callPhase === "failed") {
      initialConnectDone.value = true;
    }
  },
  { immediate: true }
);

watch(
  () => props.joiningPhase,
  (isJoining) => {
    if (isJoining) {
      initialConnectDone.value = false;
      if (joinPhaseTimeout) {
        clearTimeout(joinPhaseTimeout);
        joinPhaseTimeout = null;
      }
      // Hard guard: never block the UI forever if phase transitions are delayed.
      joinPhaseTimeout = setTimeout(() => {
        initialConnectDone.value = true;
      }, 12000);
    } else if (joinPhaseTimeout) {
      clearTimeout(joinPhaseTimeout);
      joinPhaseTimeout = null;
    }
  },
  { immediate: true }
);

onUnmounted(() => {
  if (joinPhaseTimeout) {
    clearTimeout(joinPhaseTimeout);
    joinPhaseTimeout = null;
  }
});
</script>

<template>
  <!-- Connecting screen: shown only during initial join -->
  <div v-if="showConnectingScreen" class="connecting-screen">
    <div class="connecting-card">
      <div class="connecting-spinner"></div>
      <h2>Connecting to {{ roomName || roomId }}</h2>
      <p>Setting up voice with {{ otherUsers.length }} {{ otherUsers.length === 1 ? 'user' : 'users' }}...</p>
      <button class="btn-leave" @click="emit('leave')">Cancel</button>
    </div>
    <!-- Still attach audio elements so handshake audio starts flowing immediately -->
    <div style="display:none">
      <AudioPeer v-for="user in otherUsers" :key="user.id" :stream="peerStates.get(user.id)?.stream ?? null" />
    </div>
  </div>

  <!-- Normal room UI -->
  <div v-else class="room">
    <div class="room-header">
      <div class="room-info">
        <h2>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          {{ roomName || roomId }}
        </h2>
        <span class="user-count">{{ users.length }} {{ users.length === 1 ? "user" : "users" }}</span>
      </div>
      <div class="latency-indicator" :class="rttClass" v-if="otherUsers.length > 0">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />
        </svg>
        <span v-if="latency.rtt !== null">{{ latency.rtt }}ms</span>
        <span v-else>--</span>
        <span v-if="latency.jitter !== null" class="jitter">j:{{ latency.jitter }}ms</span>
        <span v-if="latency.packetsLost > 0" class="lost">lost:{{ latency.packetsLost }}</span>
      </div>
      <button class="btn-leave" @click="emit('leave')">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
          <polyline points="16 17 21 12 16 7" />
          <line x1="21" y1="12" x2="9" y2="12" />
        </svg>
        Leave
      </button>
    </div>

    <!-- Network status banner -->
    <div v-if="!networkOnline" class="network-banner offline">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <line x1="1" y1="1" x2="23" y2="23" />
        <path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55" />
        <path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39" />
        <path d="M10.71 5.05A16 16 0 0 1 22.56 9" />
        <path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88" />
        <path d="M8.53 16.11a6 6 0 0 1 6.95 0" />
        <line x1="12" y1="20" x2="12.01" y2="20" />
      </svg>
      <span>No internet connection — audio may be interrupted</span>
    </div>

    <!-- Reconnecting banner (peer dropped mid-call) -->
    <div v-else-if="hasDisconnectedPeer" class="network-banner reconnecting">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M1 4v6h6" /><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
      </svg>
      <span>Connection unstable — reconnecting...</span>
    </div>

    <div class="participants">
      <!-- Self -->
      <div class="participant self" :class="{ muted: isMuted }">
        <div class="avatar">
          <span>{{ (myUser?.username || "?")[0].toUpperCase() }}</span>
          <div class="speaking-ring" v-if="!isMuted"></div>
        </div>
        <span class="name">{{ myUser?.username }} <span class="you-tag">(You)</span></span>
        <div class="status-icon" :class="isMuted ? 'muted-icon' : 'active-icon'">
          <svg v-if="!isMuted" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
            <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="1" y1="1" x2="23" y2="23" />
            <path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6" />
            <path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2c0 .76-.13 1.49-.35 2.17" />
          </svg>
        </div>
      </div>

      <!-- Remote peers -->
      <div
        v-for="user in otherUsers"
        :key="user.id"
        class="participant"
        :class="{ muted: user.isMuted }"
      >
        <div class="avatar">
          <span>{{ (user.username || "?")[0].toUpperCase() }}</span>
          <div class="speaking-ring" v-if="!user.isMuted"></div>
        </div>
        <span class="name">{{ user.username || "Unknown" }}</span>
        <div class="status-icon" :class="user.isMuted ? 'muted-icon' : 'active-icon'">
          <svg v-if="!user.isMuted" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
            <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="1" y1="1" x2="23" y2="23" />
            <path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6" />
            <path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2c0 .76-.13 1.49-.35 2.17" />
          </svg>
        </div>
        <AudioPeer :stream="peerStates.get(user.id)?.stream ?? null" />
      </div>
    </div>

    <!-- Controls -->
    <div class="controls">
      <button
        class="ctrl-btn"
        :class="isMuted ? 'danger' : 'active'"
        @click="emit('toggle-mute')"
        :title="isMuted ? 'Unmute' : 'Mute'"
      >
        <svg v-if="!isMuted" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
          <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          <line x1="12" y1="19" x2="12" y2="23" />
          <line x1="8" y1="23" x2="16" y2="23" />
        </svg>
        <svg v-else width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="1" y1="1" x2="23" y2="23" />
          <path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6" />
          <path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2c0 .76-.13 1.49-.35 2.17" />
          <line x1="12" y1="19" x2="12" y2="23" />
          <line x1="8" y1="23" x2="16" y2="23" />
        </svg>
      </button>

      <button
        class="ctrl-btn"
        :class="isSpeakerOn ? 'speaker-on' : 'speaker-off'"
        @click="emit('toggle-speaker')"
        :title="isSpeakerOn ? 'Switch to earpiece' : 'Switch to speaker'"
      >
        <svg v-if="isSpeakerOn" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
          <path d="M15.54 8.46a5 5 0 0 1 0 7.07" />
          <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
        </svg>
        <svg v-else width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
          <line x1="23" y1="9" x2="17" y2="15" />
          <line x1="17" y1="9" x2="23" y2="15" />
        </svg>
      </button>

      <button
        class="ctrl-btn disconnect"
        @click="emit('leave')"
        title="Disconnect"
      >
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91" />
          <line x1="23" y1="1" x2="1" y2="23" />
        </svg>
      </button>
    </div>

    <DebugPanel
      :socket-connected="socketConnected"
      :socket-id="socketId"
      :room-id="roomId"
      :recovery-state="recoveryState"
      :user-count="users.length"
      :mic-stream="micStream"
      :is-muted="isMuted"
      :peer-states="peerStates"
      :peer-connection-states="peerConnectionStates"
    />
  </div>
</template>

<style scoped>
/* --- Connecting screen --- */

.connecting-screen {
  width: 100%;
  max-width: 600px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

.connecting-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 48px 40px;
  text-align: center;
  animation: fade-in 0.3s ease;
  box-shadow: 0 0 30px rgba(0, 0, 0, 0.5);
  position: relative;
  overflow: hidden;
}

.connecting-card::after {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; height: 2px;
  background: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.connecting-card h2 {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--primary);
}

.connecting-card p {
  color: var(--text-muted);
  font-size: 0.95rem;
}

.connecting-spinner {
  width: 56px;
  height: 56px;
  border: 3px solid var(--border);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  box-shadow: 0 0 15px var(--primary-glow);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes fade-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

/* --- Room layout --- */

.room {
  width: 100%;
  max-width: 700px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* --- Banners --- */

.network-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: var(--radius);
  font-size: 0.9rem;
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  animation: banner-in 0.3s ease;
}

.network-banner.offline {
  background: rgba(255, 0, 85, 0.12);
  border: 1px solid var(--danger);
  color: var(--danger);
  box-shadow: 0 0 15px var(--danger-glow);
}

.network-banner.reconnecting {
  background: rgba(243, 156, 18, 0.12);
  border: 1px solid #f39c12;
  color: #f39c12;
  box-shadow: 0 0 15px rgba(243, 156, 18, 0.3);
}

.network-banner.reconnecting svg {
  animation: spin 1.5s linear infinite;
}

@keyframes banner-in {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.room-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px 20px;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.4);
}

.room-info h2 {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--primary);
  text-shadow: 0 0 10px var(--primary-glow);
}

.user-count {
  font-family: "Rajdhani", sans-serif;
  font-size: 0.9rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-left: 34px;
}

.latency-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.85rem;
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  padding: 4px 12px;
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.latency-indicator.good {
  color: var(--success);
  background: rgba(0, 255, 136, 0.1);
  border: 1px solid rgba(0, 255, 136, 0.3);
}

.latency-indicator.ok {
  color: #f39c12;
  background: rgba(243, 156, 18, 0.1);
  border: 1px solid rgba(243, 156, 18, 0.3);
}

.latency-indicator.bad {
  color: var(--danger);
  background: rgba(255, 0, 85, 0.1);
  border: 1px solid rgba(255, 0, 85, 0.3);
}

.latency-indicator.neutral {
  color: var(--text-muted);
  background: var(--surface);
  border: 1px solid var(--border);
}

.latency-indicator .jitter {
  opacity: 0.8;
  font-weight: 500;
}

.latency-indicator .lost {
  color: var(--danger);
  font-weight: 700;
  text-shadow: 0 0 5px rgba(255, 0, 85, 0.5);
}

.btn-leave {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 0, 85, 0.1);
  color: var(--danger);
  border: 1px solid rgba(255, 0, 85, 0.4);
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 0.9rem;
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-leave:hover {
  background: rgba(255, 0, 85, 0.2);
  border-color: var(--danger);
  box-shadow: 0 0 15px var(--danger-glow);
  transform: translateY(-1px);
}

.participants {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 16px;
}

.participant {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  transition: all 0.3s;
  overflow: hidden;
}

.participant::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; height: 3px;
  background: transparent;
  transition: background 0.3s, box-shadow 0.3s;
}

.participant.self {
  border-color: var(--border-neon);
  box-shadow: 0 0 20px rgba(0, 255, 136, 0.05);
}

.participant.self::before {
  background: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.participant.muted {
  border-color: var(--border);
  opacity: 0.8;
}

.avatar {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 12px;
  background: var(--bg);
  border: 2px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.8rem;
  font-family: "Rajdhani", sans-serif;
  font-weight: 700;
  color: var(--text);
  box-shadow: inset 0 0 10px rgba(0,0,0,0.5);
  transition: all 0.3s;
}

.participant.self .avatar {
  border-color: var(--primary);
  color: var(--primary);
  text-shadow: 0 0 10px var(--primary-glow);
}

.speaking-ring {
  position: absolute;
  inset: -6px;
  border-radius: 14px;
  border: 2px solid var(--success);
  box-shadow: 0 0 15px var(--primary-glow);
  animation: pulse 1.5s ease-in-out infinite;
}

.muted .speaking-ring {
  display: none;
}

@keyframes pulse {
  0%, 100% { opacity: 0.5; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.05); box-shadow: 0 0 20px var(--primary-glow); }
}

.name {
  font-size: 1rem;
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
  text-align: center;
}

.you-tag {
  color: var(--primary);
  font-weight: 700;
  font-size: 0.8rem;
  text-shadow: 0 0 5px var(--primary-glow);
}

.status-icon {
  position: absolute;
  top: 10px;
  right: 10px;
}

.active-icon {
  color: var(--success);
  filter: drop-shadow(0 0 5px var(--primary-glow));
}

.muted-icon {
  color: var(--danger);
  filter: drop-shadow(0 0 5px var(--danger-glow));
}

.controls {
  display: flex;
  justify-content: center;
  gap: 20px;
  padding: 24px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.4);
  position: relative;
}

.controls::before {
  content: '';
  position: absolute;
  top: -1px; left: 20%; right: 20%; height: 1px;
  background: linear-gradient(90deg, transparent, var(--border-neon), transparent);
}

.ctrl-btn {
  width: 60px;
  height: 60px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--bg);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.ctrl-btn.active {
  background: rgba(0, 255, 136, 0.1);
  color: var(--success);
  border-color: rgba(0, 255, 136, 0.3);
  box-shadow: inset 0 0 10px rgba(0, 255, 136, 0.1);
}

.ctrl-btn.active:hover {
  background: rgba(0, 255, 136, 0.2);
  border-color: var(--success);
  box-shadow: 0 0 15px var(--primary-glow);
  transform: translateY(-2px);
}

.ctrl-btn.danger {
  background: rgba(255, 0, 85, 0.1);
  color: var(--danger);
  border-color: rgba(255, 0, 85, 0.3);
  box-shadow: inset 0 0 10px rgba(255, 0, 85, 0.1);
}

.ctrl-btn.danger:hover {
  background: rgba(255, 0, 85, 0.2);
  border-color: var(--danger);
  box-shadow: 0 0 15px var(--danger-glow);
  transform: translateY(-2px);
}

.ctrl-btn.speaker-on {
  background: rgba(52, 152, 219, 0.15);
  color: #3498db;
  border-color: rgba(52, 152, 219, 0.4);
}

.ctrl-btn.speaker-on:hover {
  background: rgba(52, 152, 219, 0.25);
  box-shadow: 0 0 15px rgba(52, 152, 219, 0.4);
  transform: translateY(-2px);
}

.ctrl-btn.speaker-off {
  background: rgba(161, 161, 170, 0.1);
  color: var(--text-muted);
}

.ctrl-btn.speaker-off:hover {
  background: rgba(161, 161, 170, 0.2);
  border-color: var(--text-muted);
  transform: translateY(-2px);
}

.ctrl-btn.disconnect {
  background: var(--danger);
  color: white;
  border-color: var(--danger);
  box-shadow: 0 0 15px var(--danger-glow);
}

.ctrl-btn.disconnect:hover {
  background: #ff1a66;
  border-color: #ff1a66;
  box-shadow: 0 0 20px rgba(255, 26, 102, 0.6);
  transform: translateY(-2px);
}
</style>
