<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from "vue";
import { useSignaling } from "./composables/useSignaling";
import { useWebRTC } from "./composables/useWebRTC";
import LobbyView from "./components/LobbyView.vue";
import RoomView from "./components/RoomView.vue";

const signaling = useSignaling();
const webrtc = useWebRTC();

const pcStates = ref<Map<string, { connectionState: string; iceState: string }>>(new Map());
let pcPollTimer: ReturnType<typeof setInterval> | null = null;

function startPolling() {
  pcPollTimer = setInterval(() => {
    pcStates.value = webrtc.getPeerConnectionStates();
  }, 500);
}
function stopPolling() {
  if (pcPollTimer) { clearInterval(pcPollTimer); pcPollTimer = null; }
}
onUnmounted(stopPolling);

const serverUrl = ref(
  `https://${window.location.hostname}:3001`
);
const username = ref("");
const targetRoom = ref("general");
const connectionError = ref<string | null>(null);

onMounted(() => {
  const saved = localStorage.getItem("gcn_username");
  if (saved) username.value = saved;
});

async function handleJoin() {
  if (!username.value.trim() || !targetRoom.value.trim()) return;
  localStorage.setItem("gcn_username", username.value);

  try {
    connectionError.value = null;
    signaling.connect(serverUrl.value);

    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Connection timeout")), 5000);
      const check = setInterval(() => {
        if (signaling.connected.value) {
          clearTimeout(timeout);
          clearInterval(check);
          resolve();
        }
      }, 100);
    });

    await webrtc.startMicrophone();
    webrtc.attachSocketListeners(signaling.getRawSocket()!);
    signaling.joinRoom(targetRoom.value, username.value);
    startPolling();
  } catch (err: any) {
    connectionError.value = err.message || "Failed to connect";
  }
}

function handleLeave() {
  stopPolling();
  webrtc.detachSocketListeners(signaling.getRawSocket()!);
  webrtc.closeAllPeers();
  webrtc.stopMicrophone();
  signaling.leaveRoom();
}

watch(
  () => signaling.connected.value,
  (val) => {
    if (!val && signaling.roomId.value) {
      connectionError.value = "Disconnected from server";
    }
  }
);
</script>

<template>
  <div class="app">
    <header class="app-header">
      <div class="logo">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
          <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          <line x1="12" y1="19" x2="12" y2="23" />
          <line x1="8" y1="23" x2="16" y2="23" />
        </svg>
        <h1>GCN Voice</h1>
      </div>
      <span class="badge" :class="signaling.connected.value ? 'online' : 'offline'">
        {{ signaling.connected.value ? "Connected" : "Offline" }}
      </span>
    </header>

    <main class="app-main">
      <LobbyView
        v-if="!signaling.roomId.value"
        v-model:username="username"
        v-model:room="targetRoom"
        v-model:server="serverUrl"
        :error="connectionError || webrtc.audioError.value"
        :connecting="false"
        @join="handleJoin"
      />
      <RoomView
        v-else
        :room-id="signaling.roomId.value!"
        :users="signaling.users.value"
        :my-id="signaling.myId.value!"
        :peer-states="webrtc.peerStates"
        :is-muted="webrtc.isMuted.value"
        :socket-connected="signaling.connected.value"
        :socket-id="signaling.myId.value"
        :mic-stream="webrtc.localStream.value"
        :peer-connection-states="pcStates"
        @toggle-mute="webrtc.toggleMute()"
        @leave="handleLeave"
      />
    </main>
  </div>
</template>

<style>
*,
*::before,
*::after {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

:root {
  --bg: #0f1117;
  --surface: #1a1d27;
  --surface-hover: #242836;
  --border: #2a2e3d;
  --text: #e4e6ed;
  --text-muted: #8b8fa3;
  --primary: #6c5ce7;
  --primary-hover: #7c6ff7;
  --danger: #e74c3c;
  --danger-hover: #c0392b;
  --success: #2ecc71;
  --radius: 12px;
  --radius-sm: 8px;
}

body {
  font-family: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
}

#app {
  min-height: 100vh;
}

.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--primary);
}

.logo h1 {
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: -0.5px;
}

.badge {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 20px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.badge.online {
  background: rgba(46, 204, 113, 0.15);
  color: var(--success);
}

.badge.offline {
  background: rgba(231, 76, 60, 0.15);
  color: var(--danger);
}

.app-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
</style>
