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
}

.auth-card {
  width: 100%;
  max-width: 400px;
  background: linear-gradient(180deg, rgba(26, 29, 39, 0.95), rgba(19, 22, 31, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  padding: 40px 32px;
  text-align: center;
  box-shadow: 0 22px 50px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(8px);
}

.auth-logo {
  width: 76px;
  height: 76px;
  object-fit: contain;
  border-radius: 14px;
  margin-bottom: 16px;
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.3);
}

h2 {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 6px;
}

.subtitle {
  color: var(--text-muted);
  font-size: 0.9rem;
  margin-bottom: 2px;
}

.brand-credit {
  color: rgba(228, 230, 237, 0.7);
  font-size: 0.8rem;
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
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field input {
  background: rgba(10, 12, 18, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.95rem;
  color: var(--text);
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
}

.field input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 4px rgba(108, 92, 231, 0.2);
  transform: translateY(-1px);
}

.field input::placeholder {
  color: var(--text-muted);
  opacity: 0.6;
}

.error-msg {
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 0.85rem;
  color: var(--danger);
}

.btn-primary {
  background: linear-gradient(135deg, var(--primary), #4a7cff);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 12px;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s, filter 0.2s;
  margin-top: 4px;
  box-shadow: 0 14px 24px rgba(108, 92, 231, 0.35);
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 18px 30px rgba(108, 92, 231, 0.45);
  filter: brightness(1.03);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.auth-link {
  margin-top: 20px;
  font-size: 0.85rem;
  color: var(--text-muted);
}

.auth-link a {
  color: var(--primary);
  text-decoration: none;
  font-weight: 600;
}

.auth-link a:hover {
  text-decoration: underline;
}
</style>
