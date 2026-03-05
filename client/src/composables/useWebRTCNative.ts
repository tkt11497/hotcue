import { ref, reactive } from "vue";
import { NativeWebRTC } from "../plugins/NativeWebRTCPlugin";
import type { PluginListenerHandle } from "@capacitor/core";
import type { PeerState, SendSignalFn, PeerUnreachableFn } from "./useWebRTC";

const CLOUDFLARE_TURN_KEY_ID = "f722e547eeec974871f4e1d371fad2b2";
const CLOUDFLARE_TURN_API_TOKEN =
  "303e3cbb923f4a731454838c87f961299a1a766ce915c96fad0128c97d5afad9";
const TURN_CREDENTIAL_TTL = 86400;

const STUN_SERVERS = [
  { urls: "stun:stun.l.google.com:19302" },
  { urls: "stun:stun1.l.google.com:19302" },
];

let cachedTurnServers: { urls: string | string[]; username: string; credential: string }[] = [];
let turnCacheExpiry = 0;

async function fetchAndPushIceServers() {
  let turn = cachedTurnServers;

  if (!cachedTurnServers.length || Date.now() >= turnCacheExpiry) {
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

      if (res.ok) {
        const data = await res.json();
        const ice = data.iceServers;
        turn = [{ urls: ice.urls, username: ice.username, credential: ice.credential }];
        cachedTurnServers = turn;
        turnCacheExpiry = Date.now() + (TURN_CREDENTIAL_TTL - 300) * 1000;
        console.log("[native-rtc] TURN credentials fetched");
      }
    } catch (err) {
      console.warn("[native-rtc] TURN fetch failed, using STUN only:", err);
      turn = [];
    }
  }

  await NativeWebRTC.updateIceServers({ servers: [...STUN_SERVERS, ...turn] });
}

export function useWebRTCNative() {
  // Always null on native — audio is captured/played entirely in native layer
  const localStream = ref<MediaStream | null>(null);
  const peerStates = reactive<Map<string, PeerState>>(new Map());
  const isMuted = ref(false);
  const audioError = ref<string | null>(null);

  const OFFER_ANSWER_TIMEOUT_MS = 15_000;
  const DISCONNECT_GRACE_MS = 10_000;

  const offerTimeouts = new Map<string, ReturnType<typeof setTimeout>>();
  const disconnectTimers = new Map<string, ReturnType<typeof setTimeout>>();
  const listeners: PluginListenerHandle[] = [];

  let sendSignalFn: SendSignalFn | null = null;
  let peerUnreachableFn: PeerUnreachableFn | null = null;

  function clearOfferTimeout(peerId: string) {
    const t = offerTimeouts.get(peerId);
    if (t) {
      clearTimeout(t);
      offerTimeouts.delete(peerId);
    }
  }

  function clearDisconnectTimer(peerId: string) {
    const t = disconnectTimers.get(peerId);
    if (t) {
      clearTimeout(t);
      disconnectTimers.delete(peerId);
    }
  }

  async function startMicrophone() {
    try {
      await fetchAndPushIceServers();
      await NativeWebRTC.startMicrophone();
      isMuted.value = false;
      audioError.value = null;
      console.log("[native-rtc] microphone started");
    } catch (err: any) {
      audioError.value = err.message || "Microphone access denied";
      throw err;
    }
  }

  async function stopMicrophone() {
    await NativeWebRTC.stopMicrophone().catch(() => {});
  }

  function toggleMute() {
    isMuted.value = !isMuted.value;
    NativeWebRTC.setMuted({ muted: isMuted.value });
  }

  async function setup(sendSignal: SendSignalFn, onPeerUnreachable?: PeerUnreachableFn, _myId?: string) {
    sendSignalFn = sendSignal;
    peerUnreachableFn = onPeerUnreachable ?? null;

    for (const l of listeners) await l.remove();
    listeners.length = 0;

    listeners.push(
      await NativeWebRTC.addListener("onIceCandidate", (data) => {
        sendSignalFn?.(data.peerId, "ice-candidate", data.candidate);
      })
    );

    listeners.push(
      await NativeWebRTC.addListener("onConnectionStateChange", (data) => {
        peerStates.set(data.peerId, {
          peerId: data.peerId,
          stream: null,
          connectionState: data.state,
        });

        if (data.state === "connected") {
          clearOfferTimeout(data.peerId);
          clearDisconnectTimer(data.peerId);
        } else if (data.state === "disconnected") {
          clearDisconnectTimer(data.peerId);
          disconnectTimers.set(
            data.peerId,
            setTimeout(() => {
              const current = peerStates.get(data.peerId);
              if (current && current.connectionState !== "connected") {
                console.warn(`[native-rtc] ${data.peerId} unreachable after disconnect grace`);
                peerUnreachableFn?.(data.peerId);
              }
            }, DISCONNECT_GRACE_MS)
          );
        }
      })
    );

    listeners.push(
      await NativeWebRTC.addListener("onPeerUnreachable", (data) => {
        clearOfferTimeout(data.peerId);
        clearDisconnectTimer(data.peerId);
        console.error(`[native-rtc] connection FAILED for ${data.peerId}`);
        peerUnreachableFn?.(data.peerId);
      })
    );
  }

  async function teardown() {
    sendSignalFn = null;
    peerUnreachableFn = null;
    offerTimeouts.forEach((t) => clearTimeout(t));
    offerTimeouts.clear();
    disconnectTimers.forEach((t) => clearTimeout(t));
    disconnectTimers.clear();
    for (const l of listeners) await l.remove();
    listeners.length = 0;
  }

  async function createOffer(peerId: string) {
    peerStates.set(peerId, { peerId, stream: null, connectionState: "new" });

    try {
      await fetchAndPushIceServers();
      const sdpResult = await NativeWebRTC.createOffer({ peerId });
      sendSignalFn?.(peerId, "offer", { type: sdpResult.type, sdp: sdpResult.sdp });
      console.log(`[native-rtc] offer sent to ${peerId}`);

      clearOfferTimeout(peerId);
      offerTimeouts.set(
        peerId,
        setTimeout(() => {
          const state = peerStates.get(peerId);
          if (state && state.connectionState !== "connected") {
            console.warn(`[native-rtc] no answer from ${peerId} within ${OFFER_ANSWER_TIMEOUT_MS}ms`);
            peerUnreachableFn?.(peerId);
          }
        }, OFFER_ANSWER_TIMEOUT_MS)
      );
    } catch (err) {
      console.error("[native-rtc] createOffer failed:", err);
    }
  }

  async function handleOffer(peerId: string, offer: RTCSessionDescriptionInit) {
    peerStates.set(peerId, { peerId, stream: null, connectionState: "new" });

    try {
      await fetchAndPushIceServers();
      const answerResult = await NativeWebRTC.handleOffer({
        peerId,
        type: offer.type!,
        sdp: offer.sdp!,
      });
      sendSignalFn?.(peerId, "answer", { type: answerResult.type, sdp: answerResult.sdp });
      console.log(`[native-rtc] answer sent to ${peerId}`);
    } catch (err) {
      console.error("[native-rtc] handleOffer failed:", err);
    }
  }

  async function handleAnswer(peerId: string, answer: RTCSessionDescriptionInit) {
    clearOfferTimeout(peerId);

    try {
      await NativeWebRTC.handleAnswer({
        peerId,
        type: answer.type!,
        sdp: answer.sdp!,
      });
      console.log(`[native-rtc] answer handled for ${peerId}`);
    } catch (err) {
      console.error("[native-rtc] handleAnswer failed:", err);
    }
  }

  async function handleIceCandidate(peerId: string, candidate: RTCIceCandidateInit) {
    try {
      await NativeWebRTC.addIceCandidate({
        peerId,
        candidate: candidate.candidate!,
        sdpMid: candidate.sdpMid!,
        sdpMLineIndex: candidate.sdpMLineIndex!,
      });
    } catch (err) {
      console.warn("[native-rtc] addIceCandidate failed:", err);
    }
  }

  function removePeer(peerId: string) {
    clearOfferTimeout(peerId);
    clearDisconnectTimer(peerId);
    peerStates.delete(peerId);
    NativeWebRTC.removePeer({ peerId }).catch(() => {});
  }

  function closeAllPeers() {
    offerTimeouts.forEach((t) => clearTimeout(t));
    offerTimeouts.clear();
    disconnectTimers.forEach((t) => clearTimeout(t));
    disconnectTimers.clear();
    peerStates.clear();
    NativeWebRTC.closeAllPeers().catch(() => {});
  }

  function getPeerConnectionStates(): Map<string, { connectionState: string; iceState: string }> {
    const result = new Map<string, { connectionState: string; iceState: string }>();
    for (const [id, state] of peerStates) {
      result.set(id, {
        connectionState: state.connectionState,
        iceState: state.connectionState,
      });
    }
    return result;
  }

  async function getLatencyStats() {
    try {
      return await NativeWebRTC.getStats();
    } catch {
      return { rtt: null, jitter: null, packetsLost: 0, packetsSent: 0, packetsReceived: 0 };
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
