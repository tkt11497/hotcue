<script setup lang="ts">
import { computed } from "vue";
import type { RoomUser } from "../composables/useSignaling";
import type { PeerState } from "../composables/useWebRTC";
import AudioPeer from "./AudioPeer.vue";
import DebugPanel from "./DebugPanel.vue";

const props = defineProps<{
  roomId: string;
  roomName: string;
  users: readonly RoomUser[];
  myId: string;
  peerStates: Map<string, PeerState>;
  isMuted: boolean;
  socketConnected: boolean;
  socketId: string | null;
  micStream: MediaStream | null;
  peerConnectionStates: Map<string, { connectionState: string; iceState: string }>;
  latency: { rtt: number | null; jitter: number | null; packetsLost: number };
}>();

const emit = defineEmits<{
  "toggle-mute": [];
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
</script>

<template>
  <div class="room">
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
      >
        <div class="avatar">
          <span>{{ (user.username || "?")[0].toUpperCase() }}</span>
          <div class="speaking-ring"></div>
        </div>
        <span class="name">{{ user.username || "Unknown" }}</span>
        <div class="status-icon active-icon">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
            <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
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
      :user-count="users.length"
      :mic-stream="micStream"
      :is-muted="isMuted"
      :peer-states="peerStates"
      :peer-connection-states="peerConnectionStates"
    />
  </div>
</template>

<style scoped>
.room {
  width: 100%;
  max-width: 600px;
  display: flex;
  flex-direction: column;
  gap: 24px;
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
}

.room-info h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 1.15rem;
  font-weight: 700;
}

.user-count {
  font-size: 0.8rem;
  color: var(--text-muted);
  margin-left: 28px;
}

.latency-indicator {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 0.78rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.latency-indicator.good {
  color: #2ecc71;
  background: rgba(46, 204, 113, 0.1);
}

.latency-indicator.ok {
  color: #f39c12;
  background: rgba(243, 156, 18, 0.1);
}

.latency-indicator.bad {
  color: #e74c3c;
  background: rgba(231, 76, 60, 0.1);
}

.latency-indicator.neutral {
  color: var(--text-muted);
  background: var(--surface);
}

.latency-indicator .jitter {
  opacity: 0.7;
  font-weight: 500;
}

.latency-indicator .lost {
  color: #e74c3c;
  font-weight: 500;
}

.btn-leave {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(231, 76, 60, 0.1);
  color: var(--danger);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-leave:hover {
  background: rgba(231, 76, 60, 0.2);
}

.participants {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
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
  gap: 10px;
  transition: border-color 0.3s;
}

.participant.self {
  border-color: var(--primary);
}

.participant.muted {
  border-color: var(--border);
}

.avatar {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.3rem;
  font-weight: 700;
  color: white;
}

.speaking-ring {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid var(--success);
  animation: pulse 1.5s ease-in-out infinite;
}

.muted .speaking-ring {
  display: none;
}

@keyframes pulse {
  0%, 100% { opacity: 0.4; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.05); }
}

.name {
  font-size: 0.9rem;
  font-weight: 600;
  text-align: center;
}

.you-tag {
  color: var(--text-muted);
  font-weight: 400;
  font-size: 0.8rem;
}

.status-icon {
  position: absolute;
  top: 8px;
  right: 8px;
}

.active-icon {
  color: var(--success);
}

.muted-icon {
  color: var(--danger);
}

.controls {
  display: flex;
  justify-content: center;
  gap: 16px;
  padding: 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.ctrl-btn {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.ctrl-btn.active {
  background: rgba(46, 204, 113, 0.15);
  color: var(--success);
}

.ctrl-btn.active:hover {
  background: rgba(46, 204, 113, 0.25);
}

.ctrl-btn.danger {
  background: rgba(231, 76, 60, 0.15);
  color: var(--danger);
}

.ctrl-btn.danger:hover {
  background: rgba(231, 76, 60, 0.25);
}

.ctrl-btn.disconnect {
  background: var(--danger);
  color: white;
}

.ctrl-btn.disconnect:hover {
  background: var(--danger-hover);
}
</style>
