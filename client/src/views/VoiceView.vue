<script setup lang="ts">
import { ref, onUnmounted } from "vue";
import { useAuth } from "../composables/useAuth";
import { useSignaling } from "../composables/useSignaling";
import { useWebRTC } from "../composables/useWebRTC";
import { useNativeBackground } from "../composables/useNativeBackground";
import { db } from "../firebase";
import { doc, getDoc } from "firebase/firestore";
import LobbyView from "../components/LobbyView.vue";
import RoomView from "../components/RoomView.vue";

const { userProfile } = useAuth();
const signaling = useSignaling();
const webrtc = useWebRTC();
const nativeBg = useNativeBackground();

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

async function handleJoin(roomId: string) {
  if (!userProfile.value || !roomId.trim()) return;

  try {
    connectionError.value = null;
    joining.value = true;

    const roomSnap = await getDoc(doc(db, "rooms", roomId));
    currentRoomName.value = roomSnap.data()?.name || roomId;

    await webrtc.startMicrophone();

    webrtc.setup((to, type, payload) => {
      signaling.sendSignal(to, type, payload);
    });

    await signaling.joinRoom(roomId, userProfile.value.displayName, {
      onPeerJoined: (peerId) => {
        console.log(`[voice] peer joined: ${peerId}`);
        webrtc.createOffer(peerId);
      },
      onPeerLeft: (peerId) => {
        console.log(`[voice] peer left: ${peerId}`);
        webrtc.removePeer(peerId);
      },
      onSignal: (from, type, payload) => {
        console.log(`[voice] signal from ${from}: ${type}`);
        if (type === "offer") webrtc.handleOffer(from, payload);
        else if (type === "answer") webrtc.handleAnswer(from, payload);
        else if (type === "ice-candidate") webrtc.handleIceCandidate(from, payload);
      },
    });

    startPolling();
    nativeBg.start(roomId, {
      onToggleMute: () => {
        webrtc.toggleMute();
        nativeBg.updateMicrophoneState(webrtc.isMuted.value);
      },
      onHangup: () => handleLeave(),
    });
  } catch (err: any) {
    connectionError.value = err.message || "Failed to connect";
  } finally {
    joining.value = false;
  }
}

async function handleLeave() {
  nativeBg.stop();
  stopPolling();
  webrtc.teardown();
  webrtc.closeAllPeers();
  webrtc.stopMicrophone();
  await signaling.leaveRoom();
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
    :users="signaling.users.value"
    :my-id="signaling.myId.value!"
    :peer-states="webrtc.peerStates"
    :is-muted="webrtc.isMuted.value"
    :socket-connected="signaling.connected.value"
    :socket-id="signaling.myId.value"
    :mic-stream="webrtc.localStream.value"
    :peer-connection-states="pcStates"
    :latency="latencyInfo"
    @toggle-mute="webrtc.toggleMute(); nativeBg.updateMicrophoneState(webrtc.isMuted.value)"
    @leave="handleLeave"
  />
</template>
