import { Capacitor } from "@capacitor/core";
import { useWebRTC as useWebRTCBrowser } from "./useWebRTC";
import { useWebRTCNative } from "./useWebRTCNative";

export function useWebRTC() {
  if (Capacitor.isNativePlatform()) {
    return useWebRTCNative();
  }
  return useWebRTCBrowser();
}
