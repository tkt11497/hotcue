import { ref, reactive } from "vue";
import { useNativeCallBridge } from "./useNativeCallBridge";

export interface PeerState {
  peerId: string;
  stream: MediaStream | null;
  connectionState: string;
}

export type SendSignalFn = (to: string, type: string, payload: any) => void;
export type PeerUnreachableFn = (peerId: string) => void;

export function useWebRTC() {
  const nativeCall = useNativeCallBridge();
  const localStream = ref<MediaStream | null>(null);
  const peerStates = reactive<Map<string, PeerState>>(new Map());
  const isMuted = ref(false);
  const audioError = ref<string | null>(null);
  let stateSyncTimer: ReturnType<typeof setInterval> | null = null;

  async function startMicrophone() {
    if (nativeCall.isNative) {
      await nativeCall.ensureListener();
      syncFromNative();
      if (!stateSyncTimer) {
        stateSyncTimer = setInterval(syncFromNative, 1000);
      }
      audioError.value = null;
      return;
    }
    localStream.value = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
  }

  function stopMicrophone() {
    if (stateSyncTimer) {
      clearInterval(stateSyncTimer);
      stateSyncTimer = null;
    }
    localStream.value?.getTracks().forEach((t) => t.stop());
    localStream.value = null;
  }

  function toggleMute() {
    if (nativeCall.isNative) {
      nativeCall.toggleMute().catch((err) => console.warn("[native-call] toggle mute failed", err));
      return;
    }
    if (!localStream.value) return;
    isMuted.value = !isMuted.value;
    localStream.value.getAudioTracks().forEach((t) => (t.enabled = !isMuted.value));
  }

  function setup(sendSignal: SendSignalFn, onPeerUnreachable?: PeerUnreachableFn) {
    void sendSignal;
    void onPeerUnreachable;
  }

  function teardown() {
    if (stateSyncTimer) {
      clearInterval(stateSyncTimer);
      stateSyncTimer = null;
    }
  }

  async function createPeerConnection(peerId: string): Promise<RTCPeerConnection> {
    void peerId;
    throw new Error("Browser RTCPeerConnection is disabled in native-call mode");
  }

  function clearOfferTimeout(peerId: string) {
    void peerId;
  }

  function clearDisconnectTimer(peerId: string) {
    void peerId;
  }

  async function drainPendingCandidates(peerId: string) {
    void peerId;
  }

  async function createOffer(peerId: string) {
    void peerId;
  }

  async function handleOffer(peerId: string, offer: RTCSessionDescriptionInit) {
    void peerId;
    void offer;
  }

  async function handleAnswer(peerId: string, answer: RTCSessionDescriptionInit) {
    void peerId;
    void answer;
  }

  async function handleIceCandidate(peerId: string, candidate: RTCIceCandidateInit) {
    void peerId;
    void candidate;
  }

  function removePeer(peerId: string) {
    peerStates.delete(peerId);
  }

  function closeAllPeers() {
    peerStates.clear();
  }

  function getPeerConnectionStates(): Map<string, { connectionState: string; iceState: string }> {
    const result = new Map<string, { connectionState: string; iceState: string }>();
    for (const [id, peer] of peerStates) {
      result.set(id, {
        connectionState: peer.connectionState,
        iceState: "unknown",
      });
    }
    return result;
  }

  async function getLatencyStats(): Promise<{
    rtt: number | null;
    jitter: number | null;
    packetsLost: number;
    packetsSent: number;
    packetsReceived: number;
  }> {
    let rtt: number | null = null;
    let jitter: number | null = null;
    let packetsLost = 0;
    const packetsSent = 0;
    const packetsReceived = 0;
    const nativePeers = nativeCall.state.peers;
    if (nativePeers.length > 0) {
      const validRtts = nativePeers.map((p) => p.rttMs).filter((v) => v >= 0);
      const validJitters = nativePeers.map((p) => p.jitterMs).filter((v) => v >= 0);
      if (validRtts.length > 0) rtt = Math.round(validRtts.reduce((a, b) => a + b, 0) / validRtts.length);
      if (validJitters.length > 0) jitter = Math.round(validJitters.reduce((a, b) => a + b, 0) / validJitters.length);
      packetsLost = nativePeers.reduce((acc, p) => acc + (p.packetsLost > 0 ? p.packetsLost : 0), 0);
    }
    return { rtt, jitter, packetsLost, packetsSent, packetsReceived };
  }

  function syncFromNative() {
    if (!nativeCall.isNative) return;
    isMuted.value = nativeCall.state.isMuted;
    peerStates.clear();
    for (const peer of nativeCall.state.peers) {
      peerStates.set(peer.peerId, {
        peerId: peer.peerId,
        stream: null,
        connectionState: peer.connectionState || "new",
      });
    }
  }

  return {
    localStream,
    peerStates,
    isMuted,
    audioError,
    startMicrophone,
    stopMicrophone,
    toggleMute,
    setup,
    teardown,
    createOffer,
    handleOffer,
    handleAnswer,
    handleIceCandidate,
    closeAllPeers,
    removePeer,
    getPeerConnectionStates,
    getLatencyStats,
  };
}
