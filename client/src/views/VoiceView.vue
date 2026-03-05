<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
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

const SESSION_KEY = "gcn_voice_active_session";

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
const disconnectInfo = ref<string | null>(null);
const joining = ref(false);
const currentRoomName = ref("");
const lastRoomId = ref<string | null>(null);
let visibilityHandler: (() => void) | null = null;
let reconnecting = false;
let batteryExemptionRequested = false;

// --- Disconnect sound ---

function playDisconnectSound() {
  try {
    const ctx = new AudioContext();
    const now = ctx.currentTime;

    const osc1 = ctx.createOscillator();
    const osc2 = ctx.createOscillator();
    const gain = ctx.createGain();

    osc1.type = "sine";
    osc1.frequency.value = 480;
    osc2.type = "sine";
    osc2.frequency.value = 620;

    gain.gain.setValueAtTime(0.18, now);
    gain.gain.linearRampToValueAtTime(0.18, now + 0.15);
    gain.gain.linearRampToValueAtTime(0, now + 0.2);
    gain.gain.setValueAtTime(0.18, now + 0.3);
    gain.gain.linearRampToValueAtTime(0.18, now + 0.45);
    gain.gain.linearRampToValueAtTime(0, now + 0.55);
    gain.gain.setValueAtTime(0.15, now + 0.65);
    gain.gain.linearRampToValueAtTime(0, now + 0.9);

    osc1.frequency.setValueAtTime(480, now);
    osc1.frequency.setValueAtTime(480, now + 0.3);
    osc1.frequency.setValueAtTime(380, now + 0.65);

    osc2.frequency.setValueAtTime(620, now);
    osc2.frequency.setValueAtTime(620, now + 0.3);
    osc2.frequency.setValueAtTime(480, now + 0.65);

    osc1.connect(gain);
    osc2.connect(gain);
    gain.connect(ctx.destination);

    osc1.start(now);
    osc2.start(now);
    osc1.stop(now + 1);
    osc2.stop(now + 1);

    setTimeout(() => ctx.close(), 1200);
  } catch (e) {
    console.warn("[voice] disconnect sound failed:", e);
  }
}

// --- Session persistence (survives crash/kill/tab close) ---

function saveSession(roomId: string, roomName: string) {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify({ roomId, roomName, ts: Date.now() }));
  } catch { /* ignore */ }
}

function clearSession() {
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch { /* ignore */ }
}

function consumePreviousSession(): { roomId: string; roomName: string } | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    localStorage.removeItem(SESSION_KEY);
    const data = JSON.parse(raw);
    const age = Date.now() - (data.ts || 0);
    if (age > 24 * 60 * 60 * 1000) return null;
    return { roomId: data.roomId, roomName: data.roomName };
  } catch {
    return null;
  }
}

// --- Unclean disconnect handler ---

function handleUncleanDisconnect(roomName: string) {
  playDisconnectSound();
  disconnectInfo.value = `You were disconnected from "${roomName}"`;
  setTimeout(() => {
    if (disconnectInfo.value) disconnectInfo.value = null;
  }, 15000);
}

// --- Check for previous session on mount (crash/kill detection) ---

onMounted(() => {
  const prev = consumePreviousSession();
  if (prev && !signaling.connected.value) {
    handleUncleanDisconnect(prev.roomName);
  }
});

// --- Widget ---

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

// --- Join / Leave ---

async function handleJoin(roomId: string) {
  if (!userProfile.value || !roomId.trim()) return;

  try {
    connectionError.value = null;
    disconnectInfo.value = null;
    joining.value = true;
    lastRoomId.value = roomId;

    const roomSnap = await getDoc(doc(db, "rooms", roomId));
    currentRoomName.value = roomSnap.data()?.name || roomId;

    saveSession(roomId, currentRoomName.value);

    if (Capacitor.isNativePlatform()) {
      NativeWebRTC.setRoomName({ roomName: currentRoomName.value }).catch(() => {});

      if (!batteryExemptionRequested) {
        batteryExemptionRequested = true;
        await NativeWebRTC.requestBatteryExemption().catch(() => {});
      }
    }

    await nativeBg.start(roomId, {
      onToggleMute: () => handleToggleMute(),
      onHangup: () => handleLeave(),
    });

    if (Capacitor.isNativePlatform()) {
      await new Promise(resolve => setTimeout(resolve, 300));
    }

    await webrtc.startMicrophone();

    const myUid = auth.currentUser?.uid ?? undefined;

    let hadPeers = false;
    let allPeersLostTimer: ReturnType<typeof setTimeout> | null = null;

    await webrtc.setup(
      (to, type, payload) => signaling.sendSignal(to, type, payload),
      (peerId) => {
        console.log(`[voice] peer unreachable: ${peerId}, cleaning up`);
        webrtc.removePeer(peerId);
        signaling.removePeerDoc(peerId);

        if (hadPeers && webrtc.peerStates.size === 0) {
          if (allPeersLostTimer) clearTimeout(allPeersLostTimer);
          allPeersLostTimer = setTimeout(() => {
            allPeersLostTimer = null;
            if (webrtc.peerStates.size > 0) return;
            if (reconnecting) return;
            const othersInRoom = signaling.users.value.filter(
              (u) => u.id !== signaling.myId.value
            ).length;
            if (othersInRoom > 0) {
              console.warn("[voice] all RTC peers lost but room still has users — auto-leaving");
              const name = currentRoomName.value;
              handleLeave().then(() => handleUncleanDisconnect(name));
            }
          }, 15000);
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
        const name = currentRoomName.value;
        handleUncleanDisconnect(name);
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
  clearSession();
}
</script>

<template>
  <LobbyView
    v-if="!signaling.roomId.value"
    :error="connectionError || webrtc.audioError.value"
    :disconnect-info="disconnectInfo"
    :connecting="joining"
    @join="handleJoin"
    @dismiss-disconnect="disconnectInfo = null"
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
