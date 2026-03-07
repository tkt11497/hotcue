<script setup lang="ts">
import { onUnmounted, watch } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "./composables/useAuth";
import { useTaskNotifications } from "./composables/useTaskNotifications";

const { userProfile, authReady, isAdmin, isSecurityRole, isRoomAdmin, logout } = useAuth();
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
  --bg: #0f1117;
  --surface: #1a1d27;
  --surface-hover: #242836;
  --border: #2a2e3d;
  --text: #e4e6ed;
  --text-muted: #8b8fa3;
  --primary: #6c5ce7;
  --primary-hover: #7c6ff7;
  --danger: #e74c3c;
  --danger-hover: #c0392b;
  --success: #2ecc71;
  --radius: 12px;
  --radius-sm: 8px;
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);
  --safe-left: env(safe-area-inset-left, 0px);
  --safe-right: env(safe-area-inset-right, 0px);
}

body {
  font-family: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
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
  background:
    radial-gradient(circle at 0% 0%, rgba(83, 131, 236, 0.25), transparent 40%),
    radial-gradient(circle at 100% 100%, rgba(108, 92, 231, 0.25), transparent 40%),
    var(--bg);
}

.loading-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(26, 29, 39, 0.85);
  backdrop-filter: blur(12px);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.25);
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
  border-radius: 10px;
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.3);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.brand-copy h1 {
  font-size: 1.1rem;
  font-weight: 700;
  letter-spacing: -0.3px;
}

.brand-copy p {
  font-size: 0.74rem;
  color: var(--text-muted);
  letter-spacing: 0.3px;
}

.nav-links {
  display: flex;
  gap: 4px;
}

.nav-link {
  color: var(--text-muted);
  text-decoration: none;
  font-size: 0.85rem;
  font-weight: 600;
  padding: 8px 14px;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
  border: 1px solid transparent;
}

.nav-link:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.08);
  transform: translateY(-1px);
}

.nav-link.router-link-active {
  color: #fff;
  background: linear-gradient(135deg, var(--primary), #4a7cff);
  box-shadow: 0 10px 20px rgba(108, 92, 231, 0.35);
}

.user-info {
  font-size: 0.85rem;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 8px;
}

.role-badge {
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(108, 92, 231, 0.15);
  color: var(--primary);
}

.role-badge.admin {
  background: rgba(231, 76, 60, 0.15);
  color: #e74c3c;
}

.role-badge.holding_admin {
  background: rgba(243, 156, 18, 0.15);
  color: #f39c12;
}

.role-badge.room_admin {
  background: rgba(52, 152, 219, 0.15);
  color: #3498db;
}

.role-badge.member {
  background: rgba(46, 204, 113, 0.15);
  color: #2ecc71;
}

.role-badge.security_admin {
  background: rgba(155, 89, 182, 0.15);
  color: #9b59b6;
}

.role-badge.security {
  background: rgba(142, 68, 173, 0.15);
  color: #8e44ad;
}

.btn-logout {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-muted);
  padding: 8px 14px;
  border-radius: var(--radius-sm);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-logout:hover {
  color: #fff;
  border-color: rgba(231, 76, 60, 0.7);
  background: rgba(231, 76, 60, 0.2);
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
  border-radius: 999px;
  pointer-events: none;
  filter: blur(50px);
  opacity: 0.45;
  z-index: 0;
}

.orb-a {
  width: 280px;
  height: 280px;
  left: -90px;
  top: 10vh;
  background: rgba(71, 132, 252, 0.4);
}

.orb-b {
  width: 260px;
  height: 260px;
  right: -100px;
  bottom: 8vh;
  background: rgba(109, 217, 128, 0.28);
}

.bg-grid {
  position: fixed;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: radial-gradient(circle at center, black 20%, transparent 75%);
  opacity: 0.2;
  pointer-events: none;
  z-index: 0;
}
</style>
