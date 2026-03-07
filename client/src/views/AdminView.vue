<script setup lang="ts">
</script>

<template>
  <div class="admin-layout">
    <aside class="admin-sidebar">
      <h3>Admin Panel</h3>
      <nav class="admin-nav">
        <router-link to="/admin/rooms" class="admin-nav-link">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          Rooms
        </router-link>
        <router-link to="/admin/users" class="admin-nav-link">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
            <circle cx="9" cy="7" r="4" />
            <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
            <path d="M16 3.13a4 4 0 0 1 0 7.75" />
          </svg>
          Users
        </router-link>
        <router-link to="/admin/departments" class="admin-nav-link">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="7" width="20" height="14" rx="2" ry="2" />
            <path d="M16 3H8l-2 4h12l-2-4z" />
          </svg>
          Departments
        </router-link>
        <router-link to="/admin/access-areas" class="admin-nav-link">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 22s-8-4.5-8-11.8A8 8 0 0 1 12 2a8 8 0 0 1 8 8.2c0 7.3-8 11.8-8 11.8z" />
            <circle cx="12" cy="10" r="3" />
          </svg>
          Access Areas
        </router-link>
      </nav>
    </aside>
    <section class="admin-content">
      <router-view />
    </section>
  </div>
</template>

<style scoped>
.admin-layout {
  display: flex;
  width: 100%;
  max-width: 1200px;
  min-height: 70vh;
  gap: 24px;
  align-self: flex-start;
}

@media (max-width: 768px) {
  .admin-layout {
    flex-direction: column;
    gap: 16px;
  }
}

.admin-sidebar {
  width: 220px;
  flex-shrink: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px 16px;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.4);
  position: relative;
  overflow: hidden;
}

@media (max-width: 768px) {
  .admin-sidebar {
    width: 100%;
    padding: 16px;
  }
}

.admin-sidebar::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; height: 2px;
  background: var(--primary);
  box-shadow: 0 0 10px var(--primary-glow);
}

.admin-sidebar h3 {
  font-family: "Rajdhani", sans-serif;
  font-size: 1rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--primary);
  margin-bottom: 16px;
  text-shadow: 0 0 5px var(--primary-glow);
}

.admin-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

@media (max-width: 768px) {
  .admin-nav {
    flex-direction: row;
    flex-wrap: wrap;
  }
}

.admin-nav-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  text-decoration: none;
  font-family: "Rajdhani", sans-serif;
  font-size: 1.05rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  transition: all 0.2s;
  border: 1px solid transparent;
}

@media (max-width: 768px) {
  .admin-nav-link {
    flex: 1;
    min-width: 140px;
    justify-content: center;
  }
}

.admin-nav-link:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.08);
}

.admin-nav-link.router-link-active {
  color: #000;
  background: var(--primary);
  border-color: var(--primary);
  box-shadow: 0 0 15px var(--primary-glow);
}

.admin-content {
  flex: 1;
  min-width: 0;
  width: 100%;
}
</style>
