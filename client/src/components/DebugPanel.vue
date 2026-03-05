<script setup lang="ts">
import { ref, computed, onUnmounted } from "vue";
import type { PeerState } from "../composables/useWebRTC";

const props = defineProps<{
  socketConnected: boolean;
  socketId: string | null;
  roomId: string | null;
  userCount: number;
  micStream: MediaStream | null;
  isMuted: boolean;
  peerStates: Map<string, PeerState>;
  peerConnectionStates: Map<string, { connectionState: string; iceState: string }>;
  isNative: boolean;
}>();

const expanded = ref(true);
const testTonePlaying = ref(false);
let audioCtx: AudioContext | null = null;
let oscillator: OscillatorNode | null = null;

const micTrackInfo = computed(() => {
  if (props.isNative) {
    return props.isMuted ? "Native Audio (muted)" : "Native Audio (active)";
  }
  if (!props.micStream) return "No microphone";
  const tracks = props.micStream.getAudioTracks();
  if (tracks.length === 0) return "No audio tracks";
  const t = tracks[0];
  return `${t.label} [${t.readyState}] enabled=${t.enabled}`;
});

const micOk = computed(() => {
  if (props.isNative) return !props.isMuted;
  return !!props.micStream;
});

const peerEntries = computed(() => {
  const entries: Array<{
    id: string;
    hasStream: boolean;
    trackCount: number;
    trackDetails: string;
    connectionState: string;
    iceState: string;
  }> = [];

  for (const [id, state] of props.peerStates) {
    const pcInfo = props.peerConnectionStates.get(id);

    if (props.isNative) {
      entries.push({
        id: id.substring(0, 8) + "...",
        hasStream: pcInfo?.connectionState === "connected",
        trackCount: pcInfo?.connectionState === "connected" ? 1 : 0,
        trackDetails: pcInfo?.connectionState === "connected" ? "native audio playback" : "waiting",
        connectionState: pcInfo?.connectionState ?? state.connectionState,
        iceState: pcInfo?.iceState ?? "unknown",
      });
    } else {
      const tracks = state.stream?.getAudioTracks() ?? [];
      entries.push({
        id: id.substring(0, 8) + "...",
        hasStream: !!state.stream,
        trackCount: tracks.length,
        trackDetails: tracks.map((t) => `${t.readyState} enabled=${t.enabled}`).join(", ") || "none",
        connectionState: pcInfo?.connectionState ?? state.connectionState,
        iceState: pcInfo?.iceState ?? "unknown",
      });
    }
  }
  return entries;
});

function playTestTone() {
  if (testTonePlaying.value) {
    stopTestTone();
    return;
  }
  try {
    audioCtx = new AudioContext();
    oscillator = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    oscillator.type = "sine";
    oscillator.frequency.value = 440;
    gain.gain.value = 0.1;
    oscillator.connect(gain);
    gain.connect(audioCtx.destination);
    oscillator.start();
    testTonePlaying.value = true;
    setTimeout(() => stopTestTone(), 2000);
  } catch (e) {
    console.error("Test tone failed:", e);
  }
}

function stopTestTone() {
  oscillator?.stop();
  oscillator = null;
  audioCtx?.close();
  audioCtx = null;
  testTonePlaying.value = false;
}

onUnmounted(() => stopTestTone());
</script>

<template>
  <div class="debug-panel">
    <button class="debug-toggle" @click="expanded = !expanded">
      {{ expanded ? "Hide" : "Show" }} Debug
    </button>
    <div v-if="expanded" class="debug-content">
      <h4>Connection Pipeline <span v-if="isNative" class="native-badge">NATIVE</span></h4>

      <div class="step" :class="socketConnected ? 'ok' : 'fail'">
        <span class="dot" />
        <span><b>1. Signaling</b>: {{ socketConnected ? "Connected" : "Disconnected" }}
          <span v-if="socketId" class="mono">{{ socketId.substring(0, 8) }}...</span>
        </span>
      </div>

      <div class="step" :class="roomId ? 'ok' : 'fail'">
        <span class="dot" />
        <span><b>2. Room</b>: {{ roomId ?? "Not joined" }} ({{ userCount }} users)</span>
      </div>

      <div class="step" :class="micOk ? 'ok' : 'fail'">
        <span class="dot" />
        <span><b>3. Microphone</b>: {{ micTrackInfo }} | Muted: {{ isMuted }}</span>
      </div>

      <div v-if="peerEntries.length === 0" class="step wait">
        <span class="dot" />
        <span><b>4. Peers</b>: No peers connected</span>
      </div>

      <div v-for="(peer, i) in peerEntries" :key="peer.id" class="peer-block">
        <h5>Peer {{ i + 1 }}: {{ peer.id }}</h5>
        <div class="step" :class="peer.connectionState === 'connected' ? 'ok' : peer.connectionState === 'failed' ? 'fail' : 'wait'">
          <span class="dot" />
          <span><b>4. RTC Connection</b>: {{ peer.connectionState }}</span>
        </div>
        <div class="step" :class="peer.iceState === 'connected' || peer.iceState === 'completed' ? 'ok' : peer.iceState === 'failed' ? 'fail' : 'wait'">
          <span class="dot" />
          <span><b>5. ICE</b>: {{ peer.iceState }}</span>
        </div>
        <div class="step" :class="peer.hasStream ? 'ok' : 'fail'">
          <span class="dot" />
          <span><b>6. {{ isNative ? "Audio" : "Remote Stream" }}</b>: {{ peer.hasStream ? (isNative ? "Native Playback Active" : "Received") : "Missing" }}
            <template v-if="!isNative"> ({{ peer.trackCount }} tracks: {{ peer.trackDetails }})</template>
          </span>
        </div>
      </div>

      <div class="test-section">
        <button class="test-btn" @click="playTestTone">
          {{ testTonePlaying ? "Stop" : "Play" }} Test Tone (speakers check)
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.debug-panel {
  background: #111320;
  border: 1px solid #2a2e3d;
  border-radius: 8px;
  font-size: 0.78rem;
  overflow: hidden;
}

.debug-toggle {
  width: 100%;
  background: #1a1d27;
  color: #8b8fa3;
  border: none;
  padding: 8px 14px;
  font-size: 0.75rem;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.debug-toggle:hover { color: #e4e6ed; }

.debug-content {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

h4 {
  font-size: 0.8rem;
  margin-bottom: 4px;
  color: #8b8fa3;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.native-badge {
  font-size: 0.65rem;
  background: #6c5ce7;
  color: white;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

h5 {
  font-size: 0.75rem;
  color: #6c5ce7;
  margin-top: 6px;
}

.step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  line-height: 1.4;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ok .dot { background: #2ecc71; }
.fail .dot { background: #e74c3c; }
.wait .dot { background: #f39c12; }

.mono {
  font-family: monospace;
  color: #8b8fa3;
  font-size: 0.72rem;
}

.peer-block {
  border-top: 1px solid #2a2e3d;
  padding-top: 6px;
  margin-top: 4px;
}

.test-section {
  margin-top: 8px;
  border-top: 1px solid #2a2e3d;
  padding-top: 8px;
}

.test-btn {
  background: #2a2e3d;
  color: #e4e6ed;
  border: none;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 0.78rem;
  cursor: pointer;
}

.test-btn:hover { background: #3a3f52; }
</style>
