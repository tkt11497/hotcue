<script setup lang="ts">
import { ref, watch, onUnmounted, nextTick } from "vue";

const props = defineProps<{
  stream: MediaStream | null;
}>();

const audioEl = ref<HTMLAudioElement | null>(null);

async function attachStream(stream: MediaStream | null | undefined) {
  await nextTick();
  const el = audioEl.value;
  if (!el) return;

  if (!stream) {
    el.srcObject = null;
    return;
  }

  if (el.srcObject === stream) return;

  console.log("[audio] attaching stream, tracks:", stream.getAudioTracks().length);
  el.srcObject = stream;

  try {
    await el.play();
    console.log("[audio] playing");
  } catch (err) {
    console.warn("[audio] autoplay blocked, will retry on user gesture:", err);
    const resume = async () => {
      try {
        await el.play();
        console.log("[audio] resumed after gesture");
      } catch { /* ignore */ }
      document.removeEventListener("click", resume);
      document.removeEventListener("keydown", resume);
    };
    document.addEventListener("click", resume, { once: true });
    document.addEventListener("keydown", resume, { once: true });
  }
}

watch(() => props.stream, (s) => attachStream(s), { immediate: true });

onUnmounted(() => {
  if (audioEl.value) {
    audioEl.value.pause();
    audioEl.value.srcObject = null;
  }
});
</script>

<template>
  <audio ref="audioEl" autoplay playsinline />
</template>
