import { ref } from "vue";

let wakeLockSentinel: WakeLockSentinel | null = null;
let onMuteToggle: (() => void) | null = null;
let onHangup: (() => void) | null = null;

const active = ref(false);

export function useKeepAlive() {
  function setupMediaSession(roomId: string, callbacks: { onToggleMute: () => void; onHangup: () => void }) {
    if (!("mediaSession" in navigator)) return;

    onMuteToggle = callbacks.onToggleMute;
    onHangup = callbacks.onHangup;

    navigator.mediaSession.metadata = new MediaMetadata({
      title: "In Call",
      artist: `Room: ${roomId}`,
      album: "GCN Voice",
    });

    (navigator.mediaSession as any).setActionHandler("togglemicrophone", () => {
      onMuteToggle?.();
    });

    (navigator.mediaSession as any).setActionHandler("hangup", () => {
      onHangup?.();
    });

    try {
      navigator.mediaSession.setMicrophoneActive(true);
    } catch { /* not supported everywhere */ }

    console.log("[keepalive] media session configured for room:", roomId);
  }

  function updateMicrophoneState(isMuted: boolean) {
    if (!("mediaSession" in navigator)) return;
    try {
      navigator.mediaSession.setMicrophoneActive(!isMuted);
    } catch { /* not supported everywhere */ }
  }

  function clearMediaSession() {
    if (!("mediaSession" in navigator)) return;
    navigator.mediaSession.metadata = null;
    const actions: MediaSessionAction[] = ["play", "pause"];
    for (const action of actions) {
      try { navigator.mediaSession.setActionHandler(action, null); } catch { /* ignore */ }
    }
    try { navigator.mediaSession.setActionHandler("togglemicrophone" as MediaSessionAction, null); } catch { /* ignore */ }
    try { navigator.mediaSession.setActionHandler("hangup" as MediaSessionAction, null); } catch { /* ignore */ }
    onMuteToggle = null;
    onHangup = null;
  }

  async function requestWakeLock() {
    if (!("wakeLock" in navigator)) return;
    try {
      wakeLockSentinel = await navigator.wakeLock.request("screen");
      wakeLockSentinel.addEventListener("release", () => {
        console.log("[keepalive] wake lock released by OS");
      });
      console.log("[keepalive] wake lock acquired");
    } catch (err) {
      console.warn("[keepalive] wake lock failed:", err);
    }
  }

  async function releaseWakeLock() {
    if (wakeLockSentinel) {
      await wakeLockSentinel.release().catch(() => {});
      wakeLockSentinel = null;
    }
  }

  function handleVisibilityChange() {
    if (document.visibilityState === "visible" && active.value) {
      requestWakeLock();
    }
  }

  function start(roomId: string, callbacks: { onToggleMute: () => void; onHangup: () => void }) {
    if (active.value) return;
    active.value = true;

    setupMediaSession(roomId, callbacks);
    requestWakeLock();
    document.addEventListener("visibilitychange", handleVisibilityChange);

    console.log("[keepalive] wake lock + media session active");
  }

  function stop() {
    if (!active.value) return;
    active.value = false;

    document.removeEventListener("visibilitychange", handleVisibilityChange);
    clearMediaSession();
    releaseWakeLock();

    console.log("[keepalive] stopped");
  }

  return {
    active,
    start,
    stop,
    updateMicrophoneState,
  };
}
