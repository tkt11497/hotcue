import { ref } from "vue";

// Tiny valid MP4 video (black, silent, 1 second) base64-encoded.
// This is the proven NoSleep.js technique: Android Chrome won't suspend a tab
// that has an actively playing <video> element with an audio track.
const TINY_MP4 =
  "data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAACKBtZGF0AAAC" +
  "rgYF//+q3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE0OCByMjYwMSBhMGNkN2QzIC0gSC4yNjQvTVBF" +
  "Ry00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAxNSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gy" +
  "NjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDEx" +
  "MyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2" +
  "IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lw" +
  "PTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhy" +
  "ZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVk" +
  "X2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWln" +
  "aHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTEgc2NlbmVjdXQ9MDsg" +
  "cmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBp" +
  "cF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAD2WIhAA3//728P4FNjuY0AAAAAhBmiRsQ7OEAAAAAARBne5h" +
  "4AAAAARB3uPhAAAABEGaJGxDo4QAAAAEQZ3uYeAAAAARBne4+EAAAAQZpEbEKzhAAAAAQBne5h4AAAAEEN7j4" +
  "QAAAABBmiRsQqOEAAAABAGd7mHgAAAAQQ3uPhAAAAEGaRGxCI4QAAAAEAZnuYeAAAABBDe4+EAAACnW1vb3YA" +
  "AABsbXZoZAAAAAAAAAAAAAAAAAAAA+gAAAPoAAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
  "AAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAYnRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAD6A" +
  "AAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAA" +
  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHgbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAKABVxAAAAAAAL" +
  "WhkbHIAAAAAAAAAAHNvdW4AAAAAAAAAAAAAAABTb3VuZEhhbmRsZXIAAAABi21pbmYAAAAQc21oZAAAAAAAAAAA" +
  "ACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAU9zdGJsAAAAZ3N0c2QAAAAAAAAAAQAAAFdtcDR" +
  "hAAAAAAAAAAEAAAAAAAAAAAACABAAAAAAKAAAACgAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
  "AAAAAAAAAAAAAGVzZHMAAAAAA4CAgCIAAAAEgICAFEAVBbjYAAu4AAAADcoFgICAAhGQBoCAgAECAAAAFGh0dH" +
  "MAAAAAAAAABwAAABxzdHRzAAAAAAAAAAEAAAABAAAoAAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAYc" +
  "3RzegAAAAAAAAAGAAAAAQAAABRzdGNvAAAAAAAAAAEAAAAsAAAAYHVkdGEAAABYbWV0YQAAAAAAAAAhaGRscgAA" +
  "AAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAraWxzdAAAACOpdG9vAAAAG2RhdGEAAAABAAAAAExhdmY1Ni40MC4" +
  "xMDE=";

let keepAliveVideo: HTMLVideoElement | null = null;
let audioCtx: AudioContext | null = null;
let oscillator: OscillatorNode | null = null;
let wakeLockSentinel: WakeLockSentinel | null = null;
let onMuteToggle: (() => void) | null = null;
let onHangup: (() => void) | null = null;
let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

const active = ref(false);

export function useKeepAlive() {
  // --- Layer 1a: Video keepalive (the NoSleep.js trick) ---
  // Android Chrome won't kill a tab with an actively playing video

  function startVideoKeepAlive() {
    if (keepAliveVideo) return;

    keepAliveVideo = document.createElement("video");
    keepAliveVideo.setAttribute("playsinline", "");
    keepAliveVideo.setAttribute("muted", "");
    keepAliveVideo.muted = true;
    keepAliveVideo.loop = true;
    keepAliveVideo.src = TINY_MP4;
    keepAliveVideo.style.position = "fixed";
    keepAliveVideo.style.top = "-1px";
    keepAliveVideo.style.left = "-1px";
    keepAliveVideo.style.width = "1px";
    keepAliveVideo.style.height = "1px";
    keepAliveVideo.style.opacity = "0.01";
    document.body.appendChild(keepAliveVideo);

    keepAliveVideo.play().then(() => {
      console.log("[keepalive] video keepalive playing");
    }).catch((err) => {
      console.warn("[keepalive] video play failed:", err);
    });
  }

  function stopVideoKeepAlive() {
    if (!keepAliveVideo) return;
    keepAliveVideo.pause();
    keepAliveVideo.removeAttribute("src");
    keepAliveVideo.load();
    keepAliveVideo.remove();
    keepAliveVideo = null;
    console.log("[keepalive] video keepalive stopped");
  }

  // --- Layer 1b: Web Audio API oscillator ---
  // Generates a near-inaudible tone to keep the audio context alive

  function startAudioKeepAlive() {
    try {
      audioCtx = new AudioContext();
      oscillator = audioCtx.createOscillator();
      oscillator.frequency.value = 20;
      const silentGain = audioCtx.createGain();
      silentGain.gain.value = 0;
      oscillator.connect(silentGain);
      silentGain.connect(audioCtx.destination);
      oscillator.start();
      console.log("[keepalive] audio oscillator started (silent)");
    } catch (err) {
      console.warn("[keepalive] audio oscillator failed:", err);
    }
  }

  function stopAudioKeepAlive() {
    try {
      oscillator?.stop();
    } catch { /* already stopped */ }
    oscillator = null;
    audioCtx?.close().catch(() => {});
    audioCtx = null;
  }

  // --- Heartbeat: periodically re-poke media to prevent suspension ---

  function startHeartbeat() {
    heartbeatTimer = setInterval(() => {
      if (keepAliveVideo && keepAliveVideo.paused) {
        keepAliveVideo.play().catch(() => {});
      }
      if (audioCtx && audioCtx.state === "suspended") {
        audioCtx.resume().catch(() => {});
      }
    }, 10_000);
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  // --- Layer 2: Media Session API ---

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

    navigator.mediaSession.setActionHandler("play", () => {
      keepAliveVideo?.play().catch(() => {});
      if (audioCtx?.state === "suspended") audioCtx.resume().catch(() => {});
    });

    navigator.mediaSession.setActionHandler("pause", null);

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

  // --- Layer 3: Screen Wake Lock ---

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
      if (audioCtx?.state === "suspended") audioCtx.resume().catch(() => {});
      if (keepAliveVideo?.paused) keepAliveVideo.play().catch(() => {});
      console.log("[keepalive] tab visible again, re-acquired locks");
    }
  }

  // --- Public API ---

  function start(roomId: string, callbacks: { onToggleMute: () => void; onHangup: () => void }) {
    if (active.value) return;
    active.value = true;

    startVideoKeepAlive();
    startAudioKeepAlive();
    startHeartbeat();
    setupMediaSession(roomId, callbacks);
    requestWakeLock();
    document.addEventListener("visibilitychange", handleVisibilityChange);

    console.log("[keepalive] all layers active");
  }

  function stop() {
    if (!active.value) return;
    active.value = false;

    document.removeEventListener("visibilitychange", handleVisibilityChange);
    stopHeartbeat();
    stopVideoKeepAlive();
    stopAudioKeepAlive();
    clearMediaSession();
    releaseWakeLock();

    console.log("[keepalive] all layers stopped");
  }

  return {
    active,
    start,
    stop,
    updateMicrophoneState,
  };
}
