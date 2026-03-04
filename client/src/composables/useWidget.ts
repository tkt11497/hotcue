import { Capacitor, registerPlugin } from "@capacitor/core";

interface WidgetBridgePlugin {
  updateState(options: {
    inCall: boolean;
    roomName: string;
    isMuted: boolean;
    peerCount: number;
  }): Promise<void>;
  clearState(): Promise<void>;
  addListener(
    event: "widgetAction",
    handler: (data: { action: string }) => void
  ): Promise<{ remove: () => void }>;
}

const WidgetBridge = registerPlugin<WidgetBridgePlugin>("WidgetBridge");

const isNative = Capacitor.isNativePlatform();

export function useWidget() {
  async function updateCallState(
    roomName: string,
    isMuted: boolean,
    peerCount: number
  ) {
    if (!isNative) return;
    try {
      await WidgetBridge.updateState({
        inCall: true,
        roomName,
        isMuted,
        peerCount,
      });
    } catch (err) {
      console.warn("[widget] updateState failed:", err);
    }
  }

  async function clearCallState() {
    if (!isNative) return;
    try {
      await WidgetBridge.clearState();
    } catch (err) {
      console.warn("[widget] clearState failed:", err);
    }
  }

  async function onWidgetAction(handler: (action: string) => void) {
    if (!isNative) return { remove: () => {} };
    try {
      return await WidgetBridge.addListener("widgetAction", (data) => {
        console.log("[widget] action received:", data.action);
        handler(data.action);
      });
    } catch (err) {
      console.warn("[widget] addListener failed:", err);
      return { remove: () => {} };
    }
  }

  return {
    updateCallState,
    clearCallState,
    onWidgetAction,
  };
}
