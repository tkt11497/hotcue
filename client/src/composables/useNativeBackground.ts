import { ref } from "vue";
import { Capacitor } from "@capacitor/core";
import { useNativeCallBridge } from "./useNativeCallBridge";

const active = ref(false);

export function useNativeBackground() {
  const nativeCall = useNativeCallBridge();
  const isNative = Capacitor.isNativePlatform();

  async function start(roomId: string, callbacks: { onToggleMute: () => void; onHangup: () => void }) {
    if (active.value) return;
    active.value = true;
    void roomId;
    void callbacks;
    if (isNative) {
      await nativeCall.ensureListener();
    }
    console.log(`[native-bg] started (native=${isNative})`);
  }

  async function stop() {
    if (!active.value) return;
    active.value = false;
    void isNative;
    console.log("[native-bg] stopped");
  }

  function updateMicrophoneState(isMuted: boolean) {
    void isMuted;
  }

  async function openReliabilitySettings() {
    await nativeCall.openBatteryOptimizationSettings().catch((err) => {
      console.warn("[native-bg] failed to open battery settings:", err);
    });
  }

  return {
    active,
    isNative,
    start,
    stop,
    updateMicrophoneState,
    openReliabilitySettings,
  };
}
