<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "../composables/useAuth";

const { login } = useAuth();
const router = useRouter();

const email = ref("");
const password = ref("");
const error = ref<string | null>(null);
const loading = ref(false);

async function handleLogin() {
  if (!email.value.trim() || !password.value) return;
  try {
    error.value = null;
    loading.value = true;
    await login(email.value, password.value);
    router.push("/");
  } catch (err: any) {
    error.value = err.message || "Login failed";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <img src="/hotcue-logo.png" alt="Hot Cue logo" class="auth-logo" />
      <h2>Sign In</h2>
      <p class="subtitle">Log in to Hot Cue</p>
      <p class="brand-credit">Developed by GCN</p>

      <form @submit.prevent="handleLogin" class="auth-form">
        <div class="field">
          <label for="email">Email</label>
          <input id="email" type="email" v-model="email" placeholder="you@example.com" required />
        </div>
        <div class="field">
          <label for="password">Password</label>
          <input id="password" type="password" v-model="password" placeholder="Password" required />
        </div>

        <div v-if="error" class="error-msg">{{ error }}</div>

        <button type="submit" class="btn-primary" :disabled="loading">
          {{ loading ? "Signing in..." : "Sign In" }}
        </button>
      </form>

      <p class="auth-link">
        Contact your admin for an account.
      </p>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
  z-index: 10;
}

.auth-card {
  width: 100%;
  max-width: 400px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 40px 32px;
  text-align: center;
  box-shadow: 0 0 40px rgba(0, 0, 0, 0.6);
  position: relative;
  overflow: hidden;
}

.auth-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; height: 3px;
  background: var(--primary);
  box-shadow: 0 0 15px var(--primary-glow);
}

.auth-logo {
  width: 80px;
  height: 80px;
  object-fit: contain;
  border-radius: var(--radius-sm);
  margin-bottom: 16px;
  box-shadow: 0 0 20px var(--primary-glow);
}

h2 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1.8rem;
  font-weight: 700;
  margin-bottom: 6px;
  color: var(--primary);
  text-transform: uppercase;
  letter-spacing: 1px;
  text-shadow: 0 0 10px var(--primary-glow);
}

.subtitle {
  color: var(--text-muted);
  font-size: 0.95rem;
  margin-bottom: 4px;
}

.brand-credit {
  font-family: "Rajdhani", sans-serif;
  color: var(--primary);
  font-size: 0.85rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 26px;
}

.auth-form {
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
  font-family: "Rajdhani", sans-serif;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.field input {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  font-family: "Outfit", sans-serif;
  font-size: 1rem;
  color: var(--text);
  outline: none;
  transition: all 0.2s;
}

.field input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
  transform: translateY(-1px);
}

.field input::placeholder {
  color: var(--text-muted);
  opacity: 0.5;
}

.error-msg {
  background: rgba(255, 0, 85, 0.1);
  border: 1px solid rgba(255, 0, 85, 0.3);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.85rem;
  color: var(--danger);
  box-shadow: inset 0 0 10px rgba(255, 0, 85, 0.1);
}

.btn-primary {
  background: var(--primary);
  color: #000;
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  padding: 14px;
  font-family: "Rajdhani", sans-serif;
  font-size: 1.1rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
  margin-top: 8px;
  box-shadow: 0 0 15px var(--primary-glow);
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 0 25px var(--primary-glow);
  background: #00e67a;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  box-shadow: none;
}

.auth-link {
  margin-top: 24px;
  font-size: 0.85rem;
  color: var(--text-muted);
}

.auth-link a {
  color: var(--primary);
  text-decoration: none;
  font-weight: 600;
  transition: text-shadow 0.2s;
}

.auth-link a:hover {
  text-shadow: 0 0 8px var(--primary-glow);
}
</style>
