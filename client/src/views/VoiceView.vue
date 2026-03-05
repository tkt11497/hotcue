<script setup lang="ts">
import { ref, onUnmounted } from "vue";
import { Capacitor } from "@capacitor/core";
import { useAuth } from "../composables/useAuth";
import { useSignaling } from "../composables/useSignaling";
import { useWebRTC } from "../composables/useWebRTCBridge";
import { useNativeBackground } from "../composables/useNativeBackground";
import { useWidget } from "../composables/useWidget";
import { db, auth } from "../firebase";
import { doc, getDoc } from "firebase/firestore";
import { NativeWebRTC } from "../plugins/NativeWebRTCPlugin";
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
const lastRoomId = ref<string | null>(null);
let visibilityHandler: (() => void) | null = null;
let reconnecting = false;

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
  stopVisibilityWatcher();
});

async function handleJoin(roomId: string) {
  if (!userProfile.value || !roomId.trim()) return;

  try {
    connectionError.value = null;
    joining.value = true;
    lastRoomId.value = roomId;

    const roomSnap = await getDoc(doc(db, "rooms", roomId));
    currentRoomName.value = roomSnap.data()?.name || roomId;

    await webrtc.startMicrophone();

    const myUid = auth.currentUser?.uid ?? undefined;

    let hadPeers = false;
    let allPeersLostTimer: ReturnType<typeof setTimeout> | null = null;

    await webrtc.setup(
      (to, type, payload) => signaling.sendSignal(to, type, payload),
      (peerId) => {
        console.log(`[voice] peer unreachable: ${peerId}, cleaning up ghost`);
        webrtc.removePeer(peerId);
        signaling.removePeerDoc(peerId);

        if (hadPeers && webrtc.peerStates.size === 0) {
          if (allPeersLostTimer) clearTimeout(allPeersLostTimer);
          allPeersLostTimer = setTimeout(() => {
            allPeersLostTimer = null;
            if (webrtc.peerStates.size > 0) return;
            const othersInRoom = signaling.users.value.filter(
              (u) => u.id !== signaling.myId.value
            ).length;
            if (othersInRoom > 0) {
              console.warn("[voice] all RTC peers lost but room still has users — auto-leaving");
              handleLeave();
            }
          }, 5000);
        }
      },
      myUid
    );

    await signaling.joinRoom(roomId, userProfile.value.displayName, {
      onPeerJoined: (peerId) => {
        console.log(`[voice] peer joined: ${peerId}`);
        hadPeers = true;
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

    if (Capacitor.isNativePlatform() && myUid) {
      try {
        const idToken = await auth.currentUser!.getIdToken();
        await NativeWebRTC.startHeartbeat({
          roomId,
          userId: myUid,
          idToken,
          projectId: "hot-cue",
        });
        console.log("[voice] native heartbeat started");
      } catch (err) {
        console.warn("[voice] failed to start native heartbeat:", err);
      }
    }

    startVisibilityWatcher(roomId);
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

function startVisibilityWatcher(roomId: string) {
  stopVisibilityWatcher();

  visibilityHandler = async () => {
    if (document.visibilityState !== "visible") return;
    if (reconnecting) return;

    console.log("[voice] app returned to foreground");

    if (Capacitor.isNativePlatform() && auth.currentUser) {
      try {
        const freshToken = await auth.currentUser.getIdToken(true);
        await NativeWebRTC.updateHeartbeatToken({ idToken: freshToken });
        console.log("[voice] heartbeat token refreshed");
      } catch (err) {
        console.warn("[voice] token refresh failed:", err);
      }
    }

    if (!signaling.connected.value && lastRoomId.value && userProfile.value) {
      console.log("[voice] signaling disconnected while backgrounded, auto-reconnecting...");
      reconnecting = true;
      try {
        webrtc.teardown();
        webrtc.closeAllPeers();
        await handleJoin(lastRoomId.value);
      } catch (err) {
        console.error("[voice] auto-reconnect failed:", err);
      } finally {
        reconnecting = false;
      }
    }
  };

  document.addEventListener("visibilitychange", visibilityHandler);
}

function stopVisibilityWatcher() {
  if (visibilityHandler) {
    document.removeEventListener("visibilitychange", visibilityHandler);
    visibilityHandler = null;
  }
}

async function handleLeave() {
  stopVisibilityWatcher();
  stopWidgetSync();
  nativeBg.stop();
  stopPolling();

  if (Capacitor.isNativePlatform()) {
    NativeWebRTC.stopHeartbeat().catch(() => {});
  }

  webrtc.teardown();
  webrtc.closeAllPeers();
  webrtc.stopMicrophone();
  await signaling.leaveRoom();
  lastRoomId.value = null;
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
    :is-native="Capacitor.isNativePlatform()"
    @toggle-mute="handleToggleMute()"
    @leave="handleLeave"
  />
</template>
