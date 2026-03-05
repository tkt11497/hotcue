import { Capacitor, registerPlugin } from "@capacitor/core";
import { computed, reactive, ref } from "vue";
import { firebaseConfig } from "../firebase";

export interface NativeCallUser {
  id: string;
  username: string;
  isMuted: boolean;
}

export interface NativeCallPeer {
  peerId: string;
  connectionState: string;
  iceState: string;
  rttMs: number;
  jitterMs: number;
  packetsLost: number;
}

export interface NativeCallState {
  inCall: boolean;
  connected: boolean;
  isMuted: boolean;
  roomId: string | null;
  myId: string | null;
  myUsername: string | null;
  startedAtEpochMs: number;
  users: NativeCallUser[];
  peers: NativeCallPeer[];
}

interface NativeCallBridgePlugin {
  startCall(options: {
    roomId: string;
    userId: string;
    username: string;
    firebaseApiKey: string;
    firebaseAppId: string;
    firebaseProjectId: string;
    firebaseStorageBucket?: string;
    firebaseMessagingSenderId?: string;
  }): Promise<void>;
  toggleMute(): Promise<void>;
  hangup(): Promise<void>;
  getCallState(): Promise<{ state: NativeCallState }>;
  openBatteryOptimizationSettings(): Promise<void>;
  requestNotificationPermission(): Promise<void>;
  addListener(
    event: "callState",
    handler: (payload: { state: NativeCallState }) => void
  ): Promise<{ remove: () => void }>;
}

const NativeCallBridge = registerPlugin<NativeCallBridgePlugin>("NativeCallBridge");
const nativeCallEngineEnabled = import.meta.env.VITE_NATIVE_CALL_ENGINE !== "false";
const isNative = Capacitor.isNativePlatform() && nativeCallEngineEnabled;

const state = reactive<NativeCallState>({
  inCall: false,
  connected: false,
  isMuted: false,
  roomId: null,
  myId: null,
  myUsername: null,
  startedAtEpochMs: 0,
  users: [],
  peers: [],
});

let listenerAttached = false;
const ready = ref(false);

function applyState(next: NativeCallState) {
  state.inCall = !!next.inCall;
  state.connected = !!next.connected;
  state.isMuted = !!next.isMuted;
  state.roomId = next.roomId ?? null;
  state.myId = next.myId ?? null;
  state.myUsername = next.myUsername ?? null;
  state.startedAtEpochMs = next.startedAtEpochMs ?? 0;
  state.users = Array.isArray(next.users) ? next.users : [];
  state.peers = Array.isArray(next.peers) ? next.peers : [];
}

async function ensureListener() {
  if (!isNative || listenerAttached) return;
  listenerAttached = true;
  const res = await NativeCallBridge.getCallState().catch(() => null);
  if (res?.state) {
    applyState(res.state);
  }
  await NativeCallBridge.addListener("callState", ({ state: next }) => {
    applyState(next);
  });
  ready.value = true;
}

export function useNativeCallBridge() {
  async function startCall(roomId: string, userId: string, username: string) {
    if (!isNative) return;
    await ensureListener();
    await NativeCallBridge.requestNotificationPermission().catch(() => {});
    await NativeCallBridge.startCall({
      roomId,
      userId,
      username,
      firebaseApiKey: firebaseConfig.apiKey,
      firebaseAppId: firebaseConfig.appId,
      firebaseProjectId: firebaseConfig.projectId,
      firebaseStorageBucket: firebaseConfig.storageBucket,
      firebaseMessagingSenderId: firebaseConfig.messagingSenderId,
    });
  }

  async function toggleMute() {
    if (!isNative) return;
    await NativeCallBridge.toggleMute();
  }

  async function hangup() {
    if (!isNative) return;
    await NativeCallBridge.hangup();
  }

  async function openBatteryOptimizationSettings() {
    if (!isNative) return;
    await NativeCallBridge.openBatteryOptimizationSettings();
  }

  return {
    nativeCallEngineEnabled,
    isNative,
    ready: computed(() => ready.value),
    state,
    ensureListener,
    startCall,
    toggleMute,
    hangup,
    openBatteryOptimizationSettings,
  };
}
