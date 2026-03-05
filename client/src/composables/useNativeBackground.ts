import { ref } from "vue";
import { Capacitor } from "@capacitor/core";
import { useKeepAlive } from "./useKeepAlive";

const active = ref(false);

let fgServicePlugin: any = null;

async function loadNativePlugins() {
  if (!Capacitor.isNativePlatform()) return;
  try {
    const fg = await import("@capawesome-team/capacitor-android-foreground-service");
    fgServicePlugin = fg.ForegroundService;
  } catch (err) {
    console.warn("[native-bg] foreground-service plugin not available:", err);
  }
}

export function useNativeBackground() {
  const keepAlive = useKeepAlive();
  const isNative = Capacitor.isNativePlatform();

  async function requestPermissions() {
    if (!isNative || !fgServicePlugin) return;

    try {
      const perm = await fgServicePlugin.checkPermissions();
      if (perm.display !== "granted") {
        await fgServicePlugin.requestPermissions();
      }
    } catch (err) {
      console.warn("[native-bg] fg-service permission failed:", err);
    }
  }

  async function startNativeBackground(roomId: string) {
    await requestPermissions();

    if (fgServicePlugin) {
      try {
        await fgServicePlugin.startForegroundService({
          id: 1001,
          title: "GCN Voice",
          body: `In call - Room: ${roomId}`,
          smallIcon: "ic_launcher",
          silent: true,
          buttons: [{ title: "Hang Up", id: 1 }],
        });
        console.log("[native-bg] foreground service started");
      } catch (err) {
        console.error("[native-bg] foreground service failed:", err);
      }
    }
  }

  async function stopNativeBackground() {
    if (fgServicePlugin) {
      try {
        await fgServicePlugin.stopForegroundService();
        console.log("[native-bg] foreground service stopped");
      } catch (err) {
        console.warn("[native-bg] foreground service stop failed:", err);
      }
    }
  }

  async function start(roomId: string, callbacks: { onToggleMute: () => void; onHangup: () => void }) {
    if (active.value) return;
    active.value = true;

    if (isNative) {
      await loadNativePlugins();
      await startNativeBackground(roomId);

      if (fgServicePlugin) {
        fgServicePlugin.addListener("buttonClicked", (event: { buttonId: number }) => {
          if (event.buttonId === 1) {
            callbacks.onHangup();
          }
        });
      }
    }

    if (!isNative) {
      keepAlive.start(roomId, callbacks);
    }
    console.log(`[native-bg] started (native=${isNative})`);
  }

  async function stop() {
    if (!active.value) return;
    active.value = false;

    if (!isNative) {
      keepAlive.stop();
    }

    if (isNative) {
      if (fgServicePlugin) {
        await fgServicePlugin.removeAllListeners();
      }
      await stopNativeBackground();
    }

    console.log("[native-bg] stopped");
  }

  function updateMicrophoneState(isMuted: boolean) {
    if (!isNative) {
      keepAlive.updateMicrophoneState(isMuted);
    }
  }

  return {
    active,
    isNative,
    start,
    stop,
    updateMicrophoneState,
  };
}
