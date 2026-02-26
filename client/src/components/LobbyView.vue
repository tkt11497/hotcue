<script setup lang="ts">
import { ref } from "vue";

const props = defineProps<{
  username: string;
  room: string;
  server: string;
  error: string | null;
  connecting: boolean;
}>();

const emit = defineEmits<{
  "update:username": [value: string];
  "update:room": [value: string];
  "update:server": [value: string];
  join: [];
}>();

const showAdvanced = ref(false);

function onSubmit() {
  if (props.username.trim() && props.room.trim()) {
    emit("join");
  }
}
</script>

<template>
  <div class="lobby">
    <div class="lobby-card">
      <div class="lobby-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
          <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          <line x1="12" y1="19" x2="12" y2="23" />
          <line x1="8" y1="23" x2="16" y2="23" />
        </svg>
      </div>
      <h2>Join Voice Room</h2>
      <p class="subtitle">Connect with others on your local network</p>

      <form @submit.prevent="onSubmit" class="lobby-form">
        <div class="field">
          <label for="username">Display Name</label>
          <input
            id="username"
            type="text"
            :value="username"
            @input="emit('update:username', ($event.target as HTMLInputElement).value)"
            placeholder="Enter your name"
            autocomplete="off"
            required
          />
        </div>

        <div class="field">
          <label for="room">Room</label>
          <input
            id="room"
            type="text"
            :value="room"
            @input="emit('update:room', ($event.target as HTMLInputElement).value)"
            placeholder="Room name"
            autocomplete="off"
            required
          />
        </div>

        <button
          type="button"
          class="toggle-advanced"
          @click="showAdvanced = !showAdvanced"
        >
          {{ showAdvanced ? "Hide" : "Show" }} server settings
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            :style="{ transform: showAdvanced ? 'rotate(180deg)' : '' }"
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>

        <div v-if="showAdvanced" class="field">
          <label for="server">Server URL</label>
          <input
            id="server"
            type="text"
            :value="server"
            @input="emit('update:server', ($event.target as HTMLInputElement).value)"
            placeholder="https://localhost:3001"
          />
        </div>

        <div v-if="error" class="error-msg">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="15" y1="9" x2="9" y2="15" />
            <line x1="9" y1="9" x2="15" y2="15" />
          </svg>
          {{ error }}
        </div>

        <button type="submit" class="btn-join" :disabled="connecting || !username.trim() || !room.trim()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
            <polyline points="10 17 15 12 10 7" />
            <line x1="15" y1="12" x2="3" y2="12" />
          </svg>
          {{ connecting ? "Connecting..." : "Join Room" }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.lobby {
  width: 100%;
  max-width: 420px;
}

.lobby-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 40px 32px;
  text-align: center;
}

.lobby-icon {
  color: var(--primary);
  margin-bottom: 16px;
}

h2 {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 6px;
}

.subtitle {
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 28px;
}

.lobby-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  text-align: left;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field label {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field input {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.95rem;
  color: var(--text);
  outline: none;
  transition: border-color 0.2s;
}

.field input:focus {
  border-color: var(--primary);
}

.field input::placeholder {
  color: var(--text-muted);
  opacity: 0.6;
}

.toggle-advanced {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 0.8rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  align-self: center;
  padding: 4px;
  transition: color 0.2s;
}

.toggle-advanced:hover {
  color: var(--text);
}

.toggle-advanced svg {
  transition: transform 0.2s;
}

.error-msg {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.85rem;
  color: var(--danger);
}

.btn-join {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 12px;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
  margin-top: 4px;
}

.btn-join:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn-join:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
