<script setup lang="ts">
import { onUnmounted, watch } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "./composables/useAuth";
import { useSignaling } from "./composables/useSignaling";
import { useTaskNotifications } from "./composables/useTaskNotifications";

const { userProfile, authReady, isAdmin, isSecurityRole, isRoomAdmin, logout } = useAuth();
const { leaveRoom } = useSignaling();
const router = useRouter();
const taskNotifications = useTaskNotifications();

watch(
  () => userProfile.value,
  (profile) => {
    taskNotifications.start(profile);
  },
  { immediate: true }
);

onUnmounted(() => {
  taskNotifications.stop();
});

async function handleLogout() {
  taskNotifications.stop();
  try {
    await leaveRoom();
  } catch {
    // Ensure we still logout even if leaveRoom fails (e.g. not in a room)
  }
  await logout();
  router.push("/login");
}
</script>

<template>
  <div class="app" v-if="authReady">
    <div class="bg-orb orb-a"></div>
    <div class="bg-orb orb-b"></div>
    <div class="bg-grid"></div>
    <header class="app-header" v-if="userProfile">
      <div class="header-left">
        <router-link to="/" class="logo">
          <img src="/hotcue-logo.png" alt="Hot Cue logo" class="brand-logo" />
          <div class="brand-copy">
            <h1>Hot Cue</h1>
            <p>Developed by GCN</p>
          </div>
        </router-link>
        <nav class="nav-links">
          <router-link to="/" class="nav-link">Voice</router-link>
          <router-link v-if="isRoomAdmin || userProfile?.role === 'member'" to="/tasks" class="nav-link">Tasks</router-link>
          <router-link v-if="isSecurityRole" to="/scanner" class="nav-link">Scanner</router-link>
          <router-link v-if="isAdmin" to="/admin" class="nav-link">Admin</router-link>
        </nav>
      </div>
      <div class="header-right">
        <span class="user-info">
          {{ userProfile.displayName }}
          <span class="role-badge" :class="userProfile.role">{{ userProfile.role.replace("_", " ") }}</span>
        </span>
        <button class="btn-logout" @click="handleLogout">Logout</button>
      </div>
    </header>

    <main class="app-main">
      <router-view />
    </main>
  </div>
  <div v-else class="loading-screen">
    <p>Loading...</p>
  </div>
</template>

<style>
*,
*::before,
*::after {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

:root {
  --bg: #0b0c10;
  --surface: #18181b;
  --surface-hover: #27272a;
  --border: #27272a;
  --border-neon: rgba(0, 255, 136, 0.3);
  --text: #f4f4f5;
  --text-muted: #a1a1aa;
  --primary: #00ff88;
  --primary-hover: #00e67a;
  --primary-glow: rgba(0, 255, 136, 0.4);
  --danger: #ff0055;
  --danger-hover: #e6004c;
  --danger-glow: rgba(255, 0, 85, 0.4);
  --success: #00ff88;
  --radius: 8px;
  --radius-sm: 4px;
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);
  --safe-left: env(safe-area-inset-left, 0px);
  --safe-right: env(safe-area-inset-right, 0px);
}

body {
  font-family: "Outfit", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
}

h1, h2, h3, h4, h5, h6 {
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

#app {
  min-height: 100vh;
}

.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  padding-top: var(--safe-top);
  padding-bottom: var(--safe-bottom);
  padding-left: var(--safe-left);
  padding-right: var(--safe-right);
  background: var(--bg);
}

.loading-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 2px;
  font-size: 1.2rem;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  border-bottom: 1px solid var(--border);
  background: rgba(24, 24, 27, 0.85);
  backdrop-filter: blur(12px);
  box-shadow: 0 4px 30px rgba(0, 0, 0, 0.5);
  gap: 16px;
  flex-wrap: wrap;
  position: sticky;
  top: 0;
  z-index: 20;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 24px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text);
  text-decoration: none;
  transition: transform 0.2s ease;
}

.logo:hover {
  transform: translateY(-1px);
}

.brand-logo {
  width: 44px;
  height: 44px;
  object-fit: contain;
  border-radius: var(--radius-sm);
  box-shadow: 0 0 15px var(--primary-glow);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.brand-copy h1 {
  font-size: 1.3rem;
  font-weight: 700;
  letter-spacing: 1px;
  margin: 0;
  text-shadow: 0 0 10px rgba(255, 255, 255, 0.2);
}

.brand-copy p {
  font-size: 0.7rem;
  color: var(--primary);
  letter-spacing: 1px;
  text-transform: uppercase;
  font-family: "Rajdhani", sans-serif;
  font-weight: 600;
}

.nav-links {
  display: flex;
  gap: 8px;
}

.nav-link {
  color: var(--text-muted);
  text-decoration: none;
  font-size: 0.9rem;
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
  border: 1px solid transparent;
  position: relative;
  overflow: hidden;
}

.nav-link:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.08);
}

.nav-link.router-link-active {
  color: #000;
  background: var(--primary);
  border-color: var(--primary);
  box-shadow: 0 0 15px var(--primary-glow);
}

.user-info {
  font-size: 0.85rem;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 12px;
}

.role-badge {
  font-family: "Rajdhani", sans-serif;
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  padding: 2px 10px;
  border-radius: var(--radius-sm);
  background: rgba(0, 255, 136, 0.15);
  color: var(--primary);
  border: 1px solid var(--primary-glow);
}

.role-badge.admin {
  background: rgba(255, 0, 85, 0.15);
  color: var(--danger);
  border-color: var(--danger-glow);
}

.role-badge.holding_admin {
  background: rgba(243, 156, 18, 0.15);
  color: #f39c12;
  border-color: rgba(243, 156, 18, 0.3);
}

.role-badge.room_admin {
  background: rgba(52, 152, 219, 0.15);
  color: #3498db;
  border-color: rgba(52, 152, 219, 0.3);
}

.role-badge.member {
  background: rgba(0, 255, 136, 0.15);
  color: var(--success);
  border-color: var(--primary-glow);
}

.role-badge.security_admin,
.role-badge.security {
  background: rgba(155, 89, 182, 0.15);
  color: #9b59b6;
  border-color: rgba(155, 89, 182, 0.3);
}

.btn-logout {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-muted);
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  font-size: 0.85rem;
  font-family: "Rajdhani", sans-serif;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-logout:hover {
  color: var(--danger);
  border-color: var(--danger);
  background: rgba(255, 0, 85, 0.1);
  box-shadow: 0 0 15px var(--danger-glow);
}

.app-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  z-index: 2;
  padding: 24px;
}

.bg-orb {
  position: fixed;
  border-radius: 50%;
  pointer-events: none;
  filter: blur(80px);
  opacity: 0.25;
  z-index: 0;
}

.orb-a {
  width: 400px;
  height: 400px;
  left: -150px;
  top: 10vh;
  background: var(--primary);
}

.orb-b {
  width: 300px;
  height: 300px;
  right: -100px;
  bottom: 8vh;
  background: var(--danger);
}

.bg-grid {
  position: fixed;
  inset: 0;
  background-image:
    linear-gradient(rgba(0, 255, 136, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 255, 136, 0.03) 1px, transparent 1px);
  background-size: 30px 30px;
  mask-image: radial-gradient(circle at center, black 30%, transparent 80%);
  opacity: 1;
  pointer-events: none;
  z-index: 0;
}
</style>
