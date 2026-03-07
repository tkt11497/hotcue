<script setup lang="ts">
import { useRouter } from "vue-router";
import { useAuth } from "./composables/useAuth";

const { userProfile, authReady, isAdmin, isSecurityRole, isRoomAdmin, logout } = useAuth();
const router = useRouter();

async function handleLogout() {
  await logout();
  router.push("/login");
}
</script>

<template>
  <div class="app" v-if="authReady">
    <header class="app-header" v-if="userProfile">
      <div class="header-left">
        <router-link to="/" class="logo">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
            <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
            <line x1="12" y1="19" x2="12" y2="23" />
            <line x1="8" y1="23" x2="16" y2="23" />
          </svg>
          <h1>GCN Voice</h1>
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
  padding-top: var(--safe-top);
  padding-bottom: var(--safe-bottom);
  padding-left: var(--safe-left);
  padding-right: var(--safe-right);
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
  padding: 12px 24px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
  gap: 16px;
  flex-wrap: wrap;
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
  gap: 10px;
  color: var(--primary);
  text-decoration: none;
}

.logo h1 {
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: -0.5px;
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
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  transition: all 0.2s;
}

.nav-link:hover {
  color: var(--text);
  background: var(--surface-hover);
}

.nav-link.router-link-active {
  color: var(--primary);
  background: rgba(108, 92, 231, 0.1);
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
  background: none;
  border: 1px solid var(--border);
  color: var(--text-muted);
  padding: 6px 14px;
  border-radius: var(--radius-sm);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-logout:hover {
  color: var(--danger);
  border-color: var(--danger);
}

.app-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
</style>
