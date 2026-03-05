import { ref } from "vue";
import { useNativeCallBridge } from "./useNativeCallBridge";

const active = ref(false);

export function useKeepAlive() {
  const nativeCall = useNativeCallBridge();

  function start(roomId: string, callbacks: { onToggleMute: () => void; onHangup: () => void }) {
    void roomId;
    void callbacks;
    active.value = true;
    nativeCall.ensureListener().catch(() => {});
  }

  function stop() {
    active.value = false;
  }

  function updateMicrophoneState(isMuted: boolean) {
    void isMuted;
  }

  return {
    active,
    start,
    stop,
    updateMicrophoneState,
  };
}
