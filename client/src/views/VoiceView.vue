<script setup lang="ts">
import { ref, onUnmounted, watch } from "vue";
import { useAuth } from "../composables/useAuth";
import { useSignaling } from "../composables/useSignaling";
import { useWebRTC } from "../composables/useWebRTC";
import { useNativeBackground } from "../composables/useNativeBackground";
import { useWidget } from "../composables/useWidget";
import { db } from "../firebase";
import { doc, getDoc } from "firebase/firestore";
import LobbyView from "../components/LobbyView.vue";
import RoomView from "../components/RoomView.vue";

const { userProfile } = useAuth();
const signaling = useSignaling();
const webrtc = useWebRTC();
const nativeBg = useNativeBackground();
const widget = useWidget();

const pcStates = ref<Map<string, { connectionState: string; iceState: string }>>(new Map());
const latencyInfo = ref<{ rtt: number | null; jitter: number | null; packetsLost: number }>({
  rtt: null, jitter: null, packetsLost: 0,
});
let pcPollTimer: ReturnType<typeof setInterval> | null = null;

function startPolling() {
  pcPollTimer = setInterval(async () => {
    pcStates.value = webrtc.getPeerConnectionStates();
    latencyInfo.value = await webrtc.getLatencyStats();
  }, 1000);
}
function stopPolling() {
  if (pcPollTimer) { clearInterval(pcPollTimer); pcPollTimer = null; }
  latencyInfo.value = { rtt: null, jitter: null, packetsLost: 0 };
}
onUnmounted(stopPolling);

const connectionError = ref<string | null>(null);
const joining = ref(false);
const currentRoomName = ref("");

watch(
  () => [signaling.roomId.value, signaling.roomName.value] as const,
  ([activeRoomId, activeRoomName]) => {
    if (!activeRoomId) {
      currentRoomName.value = "";
      return;
    }
    currentRoomName.value = activeRoomName || activeRoomId;
  },
  { immediate: true }
);

let widgetListenerCleanup: { remove: () => void } | undefined;

widget.onWidgetAction((action) => {
  if (action === "toggleMute" && signaling.roomId.value) {
    handleToggleMute();
  } else if (action === "hangup" && signaling.roomId.value) {
    handleLeave();
  }
}).then((handle) => {
  widgetListenerCleanup = handle;
});

function handleToggleMute() {
  webrtc.toggleMute();
  const muted = webrtc.isMuted.value;
  nativeBg.updateMicrophoneState(muted);
  signaling.updateMuteState(muted);
  syncWidget();
}

function handleToggleSpeaker() {
  webrtc.toggleSpeaker();
}

function syncWidget() {
  if (!signaling.roomId.value) return;
  widget.updateCallState(
    currentRoomName.value || signaling.roomId.value,
    webrtc.isMuted.value,
    webrtc.peerStates.size
  );
}

let widgetSyncTimer: ReturnType<typeof setInterval> | null = null;

function startWidgetSync() {
  syncWidget();
  widgetSyncTimer = setInterval(syncWidget, 3000);
}

function stopWidgetSync() {
  if (widgetSyncTimer) {
    clearInterval(widgetSyncTimer);
    widgetSyncTimer = null;
  }
  widget.clearCallState();
}

onUnmounted(() => {
  widgetListenerCleanup?.remove();
  stopWidgetSync();
});

async function handleJoin(roomId: string) {
  if (!userProfile.value || !roomId.trim()) return;

  try {
    connectionError.value = null;
    joining.value = true;

    const roomSnap = await getDoc(doc(db, "rooms", roomId));
    currentRoomName.value = roomSnap.data()?.name || roomId;

    await webrtc.startMicrophone();

    webrtc.setup(
      () => {},
      () => {}
    );

    await signaling.joinRoom(roomId, userProfile.value.displayName, {
      onPeerJoined: () => {},
      onPeerLeft: () => {},
      onSignal: () => {},
    }, currentRoomName.value || roomId);

    startPolling();
    startWidgetSync();
    nativeBg.start(roomId, {
      onToggleMute: () => handleToggleMute(),
      onHangup: () => handleLeave(),
    });
  } catch (err: any) {
    connectionError.value = err.message || "Failed to connect";
  } finally {
    joining.value = false;
  }
}

async function handleLeave() {
  stopWidgetSync();
  nativeBg.stop();
  stopPolling();
  webrtc.teardown();
  webrtc.closeAllPeers();
  webrtc.stopMicrophone();
  await signaling.leaveRoom();
  currentRoomName.value = "";
}
</script>

<template>
  <LobbyView
    v-if="!signaling.roomId.value"
    :error="connectionError || webrtc.audioError.value"
    :connecting="joining"
    @join="handleJoin"
  />
  <RoomView
    v-else
    :room-id="signaling.roomId.value!"
    :room-name="currentRoomName"
    :joining-phase="joining"
    :call-phase="signaling.callPhase.value"
    :recovery-state="signaling.recoveryState.value"
    :users="signaling.users.value"
    :my-id="signaling.myId.value!"
    :peer-states="webrtc.peerStates"
    :is-muted="webrtc.isMuted.value"
    :is-speaker-on="webrtc.isSpeakerOn.value"
    :socket-connected="signaling.connected.value"
    :socket-id="signaling.myId.value"
    :mic-stream="webrtc.localStream.value"
    :peer-connection-states="pcStates"
    :latency="latencyInfo"
    @toggle-mute="handleToggleMute()"
    @toggle-speaker="handleToggleSpeaker()"
    @leave="handleLeave"
  />
</template>
