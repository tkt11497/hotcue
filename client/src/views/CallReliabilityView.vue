<script setup lang="ts">
import { useNativeBackground } from "../composables/useNativeBackground";

const nativeBg = useNativeBackground();

async function openBatterySettings() {
  await nativeBg.openReliabilitySettings();
}
</script>

<template>
  <div class="reliability">
    <div class="card">
      <h2>Call Reliability</h2>
      <p>
        To keep calls alive on lock screen, allow notifications and disable battery optimization for Hot Cue.
      </p>
      <ul>
        <li>Allow notification permission for call controls.</li>
        <li>Set battery mode to Unrestricted for this app.</li>
        <li>Disable vendor battery cleaners for long calls.</li>
      </ul>
      <button class="btn" @click="openBatterySettings">Open Battery Settings</button>
    </div>
  </div>
</template>

<style scoped>
.reliability {
  display: flex;
  justify-content: center;
  width: 100%;
}
.card {
  width: 100%;
  max-width: 560px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
}
.btn {
  margin-top: 12px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-hover);
  cursor: pointer;
}
</style>
