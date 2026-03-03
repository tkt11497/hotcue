import { ref, reactive } from "vue";

export interface PeerState {
  peerId: string;
  stream: MediaStream | null;
  connectionState: string;
}

export type SendSignalFn = (to: string, type: string, payload: any) => void;

const CLOUDFLARE_TURN_KEY_ID = "f722e547eeec974871f4e1d371fad2b2";
const CLOUDFLARE_TURN_API_TOKEN = "303e3cbb923f4a731454838c87f961299a1a766ce915c96fad0128c97d5afad9";
const TURN_CREDENTIAL_TTL = 86400;

const STUN_SERVERS: RTCIceServer[] = [
  { urls: "stun:stun.l.google.com:19302" },
  { urls: "stun:stun1.l.google.com:19302" },
];

let cachedTurnServers: RTCIceServer[] = [];
let turnCacheExpiry = 0;

async function fetchTurnCredentials(): Promise<RTCIceServer[]> {
  if (cachedTurnServers.length && Date.now() < turnCacheExpiry) {
    return cachedTurnServers;
  }

  try {
    const res = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${CLOUDFLARE_TURN_KEY_ID}/credentials/generate`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${CLOUDFLARE_TURN_API_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: TURN_CREDENTIAL_TTL }),
      }
    );

    if (!res.ok) throw new Error(`TURN credential fetch failed: ${res.status}`);

    const data = await res.json();
    const iceServers = data.iceServers;

    cachedTurnServers = [
      { urls: iceServers.urls, username: iceServers.username, credential: iceServers.credential },
    ];
    turnCacheExpiry = Date.now() + (TURN_CREDENTIAL_TTL - 300) * 1000;

    console.log("[rtc] TURN credentials fetched, expires in", TURN_CREDENTIAL_TTL - 300, "s");
    return cachedTurnServers;
  } catch (err) {
    console.warn("[rtc] failed to fetch TURN credentials, using STUN only:", err);
    return [];
  }
}

async function getIceServers(): Promise<RTCIceServer[]> {
  const turn = await fetchTurnCredentials();
  return [...STUN_SERVERS, ...turn];
}

export function useWebRTC() {
  const localStream = ref<MediaStream | null>(null);
  const peerStates = reactive<Map<string, PeerState>>(new Map());
  const isMuted = ref(false);
  const audioError = ref<string | null>(null);

  const peerConnections = new Map<string, RTCPeerConnection>();
  const pendingCandidates = new Map<string, RTCIceCandidateInit[]>();
  let sendSignalFn: SendSignalFn | null = null;

  async function startMicrophone() {
    try {
      localStream.value = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      });
      audioError.value = null;
      console.log("[mic] started, tracks:", localStream.value.getAudioTracks().length);
    } catch (err: any) {
      audioError.value = err.message || "Microphone access denied";
      throw err;
    }
  }

  function stopMicrophone() {
    localStream.value?.getTracks().forEach((t) => t.stop());
    localStream.value = null;
  }

  function toggleMute() {
    if (!localStream.value) return;
    isMuted.value = !isMuted.value;
    localStream.value.getAudioTracks().forEach((t) => {
      t.enabled = !isMuted.value;
    });
  }

  function setup(sendSignal: SendSignalFn) {
    sendSignalFn = sendSignal;
  }

  function teardown() {
    sendSignalFn = null;
  }

  async function createPeerConnection(peerId: string): Promise<RTCPeerConnection> {
    const existing = peerConnections.get(peerId);
    if (existing) {
      console.log(`[rtc] closing existing PC for ${peerId}`);
      existing.close();
      peerConnections.delete(peerId);
    }

    const iceServers = await getIceServers();
    const pc = new RTCPeerConnection({ iceServers });
    peerConnections.set(peerId, pc);
    pendingCandidates.set(peerId, []);

    if (localStream.value) {
      localStream.value.getTracks().forEach((track) => {
        pc.addTrack(track, localStream.value!);
      });
      console.log(`[rtc] added ${localStream.value.getTracks().length} local track(s) for ${peerId}`);
    } else {
      console.warn(`[rtc] WARNING: no local stream when creating PC for ${peerId}`);
    }

    peerStates.set(peerId, {
      peerId,
      stream: null,
      connectionState: "new",
    });

    pc.ontrack = (event) => {
      console.log(`[rtc] ontrack from ${peerId}, streams: ${event.streams.length}, track: ${event.track.kind} ${event.track.readyState}`);
      const remoteStream = event.streams[0] || new MediaStream([event.track]);
      peerStates.set(peerId, {
        peerId,
        stream: remoteStream,
        connectionState: pc.connectionState,
      });
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        console.log(`[rtc] sending ICE candidate to ${peerId}: ${event.candidate.candidate.substring(0, 50)}...`);
        sendSignalFn?.(peerId, "ice-candidate", event.candidate.toJSON());
      } else {
        console.log(`[rtc] ICE gathering complete for ${peerId}`);
      }
    };

    pc.onconnectionstatechange = () => {
      console.log(`[rtc] ${peerId} connectionState: ${pc.connectionState}`);
      const state = peerStates.get(peerId);
      if (state) {
        peerStates.set(peerId, { ...state, connectionState: pc.connectionState });
      }
      if (pc.connectionState === "failed") {
        console.error(`[rtc] connection FAILED for ${peerId}`);
      }
    };

    pc.oniceconnectionstatechange = () => {
      console.log(`[rtc] ${peerId} iceConnectionState: ${pc.iceConnectionState}`);
    };

    pc.onicegatheringstatechange = () => {
      console.log(`[rtc] ${peerId} iceGatheringState: ${pc.iceGatheringState}`);
    };

    pc.onsignalingstatechange = () => {
      console.log(`[rtc] ${peerId} signalingState: ${pc.signalingState}`);
    };

    return pc;
  }

  async function drainPendingCandidates(peerId: string) {
    const pc = peerConnections.get(peerId);
    const queued = pendingCandidates.get(peerId);
    if (!pc || !queued || queued.length === 0) return;

    console.log(`[rtc] draining ${queued.length} queued ICE candidates for ${peerId}`);
    for (const candidate of queued) {
      try {
        await pc.addIceCandidate(candidate);
      } catch (err) {
        console.warn(`[rtc] failed to add queued candidate:`, err);
      }
    }
    pendingCandidates.set(peerId, []);
  }

  async function createOffer(peerId: string) {
    console.log(`[rtc] === createOffer for ${peerId} ===`);
    const pc = await createPeerConnection(peerId);

    try {
      const offer = await pc.createOffer();
      console.log(`[rtc] offer created, sdp length: ${offer.sdp?.length}`);
      await pc.setLocalDescription(offer);
      console.log(`[rtc] local description set, signalingState: ${pc.signalingState}`);

      sendSignalFn?.(peerId, "offer", {
        type: pc.localDescription!.type,
        sdp: pc.localDescription!.sdp,
      });
      console.log(`[rtc] offer sent to ${peerId}`);
    } catch (err) {
      console.error(`[rtc] createOffer failed:`, err);
    }
  }

  async function handleOffer(peerId: string, offer: RTCSessionDescriptionInit) {
    console.log(`[rtc] === handleOffer from ${peerId}, sdp length: ${offer.sdp?.length} ===`);
    const pc = await createPeerConnection(peerId);

    try {
      await pc.setRemoteDescription(offer);
      console.log(`[rtc] remote description set, signalingState: ${pc.signalingState}`);

      await drainPendingCandidates(peerId);

      const answer = await pc.createAnswer();
      console.log(`[rtc] answer created, sdp length: ${answer.sdp?.length}`);
      await pc.setLocalDescription(answer);
      console.log(`[rtc] local description set, signalingState: ${pc.signalingState}`);

      sendSignalFn?.(peerId, "answer", {
        type: pc.localDescription!.type,
        sdp: pc.localDescription!.sdp,
      });
      console.log(`[rtc] answer sent to ${peerId}`);
    } catch (err) {
      console.error(`[rtc] handleOffer failed:`, err);
    }
  }

  async function handleAnswer(peerId: string, answer: RTCSessionDescriptionInit) {
    console.log(`[rtc] === handleAnswer from ${peerId}, sdp length: ${answer.sdp?.length} ===`);
    const pc = peerConnections.get(peerId);
    if (!pc) {
      console.error(`[rtc] no PC found for ${peerId} when handling answer`);
      return;
    }

    try {
      await pc.setRemoteDescription(answer);
      console.log(`[rtc] remote description set, signalingState: ${pc.signalingState}`);
      await drainPendingCandidates(peerId);
    } catch (err) {
      console.error(`[rtc] handleAnswer failed:`, err);
    }
  }

  async function handleIceCandidate(peerId: string, candidate: RTCIceCandidateInit) {
    const pc = peerConnections.get(peerId);

    if (!pc || !pc.remoteDescription) {
      console.log(`[rtc] queuing ICE candidate for ${peerId} (PC ${pc ? "exists" : "missing"}, remoteDesc ${pc?.remoteDescription ? "set" : "not set"})`);
      const queue = pendingCandidates.get(peerId) || [];
      queue.push(candidate);
      pendingCandidates.set(peerId, queue);
      return;
    }

    try {
      await pc.addIceCandidate(candidate);
    } catch (err) {
      console.warn(`[rtc] addIceCandidate failed:`, err);
    }
  }

  function removePeer(peerId: string) {
    const pc = peerConnections.get(peerId);
    if (pc) {
      pc.close();
      peerConnections.delete(peerId);
    }
    pendingCandidates.delete(peerId);
    peerStates.delete(peerId);
  }

  function closeAllPeers() {
    peerConnections.forEach((pc) => pc.close());
    peerConnections.clear();
    pendingCandidates.clear();
    peerStates.clear();
  }

  function getPeerConnectionStates(): Map<string, { connectionState: string; iceState: string }> {
    const result = new Map<string, { connectionState: string; iceState: string }>();
    for (const [id, pc] of peerConnections) {
      result.set(id, {
        connectionState: pc.connectionState,
        iceState: pc.iceConnectionState,
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
    let packetsSent = 0;
    let packetsReceived = 0;

    for (const pc of peerConnections.values()) {
      try {
        const stats = await pc.getStats();
        stats.forEach((report) => {
          if (report.type === "candidate-pair" && report.state === "succeeded") {
            if (report.currentRoundTripTime != null) {
              rtt = Math.round(report.currentRoundTripTime * 1000);
            }
          }
          if (report.type === "inbound-rtp" && report.kind === "audio") {
            if (report.jitter != null) {
              jitter = Math.round(report.jitter * 1000);
            }
            packetsLost += report.packetsLost ?? 0;
            packetsReceived += report.packetsReceived ?? 0;
          }
          if (report.type === "outbound-rtp" && report.kind === "audio") {
            packetsSent += report.packetsSent ?? 0;
          }
        });
      } catch { /* pc may be closing */ }
    }

    return { rtt, jitter, packetsLost, packetsSent, packetsReceived };
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
