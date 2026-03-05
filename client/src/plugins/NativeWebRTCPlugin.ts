import { registerPlugin } from "@capacitor/core";
import type { PluginListenerHandle } from "@capacitor/core";

export interface IceServerConfig {
  urls: string | string[];
  username?: string;
  credential?: string;
}

export interface SdpResult {
  type: string;
  sdp: string;
}

export interface IceCandidateEvent {
  peerId: string;
  candidate: {
    candidate: string;
    sdpMid: string;
    sdpMLineIndex: number;
  };
}

export interface ConnectionStateEvent {
  peerId: string;
  state: string;
}

export interface PeerUnreachableEvent {
  peerId: string;
}

export interface NativeWebRTCPluginInterface {
  updateIceServers(options: { servers: IceServerConfig[] }): Promise<void>;
  startMicrophone(): Promise<void>;
  stopMicrophone(): Promise<void>;
  setMuted(options: { muted: boolean }): Promise<void>;
  setSpeakerphone(options: { enabled: boolean }): Promise<void>;
  createOffer(options: { peerId: string }): Promise<SdpResult>;
  handleOffer(options: { peerId: string; type: string; sdp: string }): Promise<SdpResult>;
  handleAnswer(options: { peerId: string; type: string; sdp: string }): Promise<void>;
  addIceCandidate(options: {
    peerId: string;
    candidate: string;
    sdpMid: string;
    sdpMLineIndex: number;
  }): Promise<void>;
  setRoomName(options: { roomName: string }): Promise<void>;
  requestBatteryExemption(): Promise<void>;
  removePeer(options: { peerId: string }): Promise<void>;
  closeAllPeers(): Promise<void>;

  startHeartbeat(options: {
    roomId: string;
    userId: string;
    idToken: string;
    projectId?: string;
  }): Promise<void>;
  stopHeartbeat(): Promise<void>;
  updateHeartbeatToken(options: { idToken: string }): Promise<void>;
  getStats(): Promise<{
    rtt: number | null;
    jitter: number | null;
    packetsLost: number;
    packetsSent: number;
    packetsReceived: number;
  }>;
  getPeerStates(): Promise<Record<string, { connectionState: string; iceState: string }>>;

  addListener(
    event: "onIceCandidate",
    handler: (data: IceCandidateEvent) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    event: "onConnectionStateChange",
    handler: (data: ConnectionStateEvent) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    event: "onPeerUnreachable",
    handler: (data: PeerUnreachableEvent) => void
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export const NativeWebRTC = registerPlugin<NativeWebRTCPluginInterface>("NativeWebRTC");
