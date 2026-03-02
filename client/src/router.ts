import { createRouter, createWebHistory } from "vue-router";
import { auth, db } from "./firebase";
import { doc, getDoc } from "firebase/firestore";
import { onAuthStateChanged } from "firebase/auth";

function waitForAuth(): Promise<void> {
  return new Promise((resolve) => {
    if (auth.currentUser !== undefined) {
      const unsub = onAuthStateChanged(auth, () => {
        unsub();
        resolve();
      });
    } else {
      resolve();
    }
  });
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("./views/LoginView.vue"),
      meta: { guest: true },
    },
    {
      path: "/",
      name: "voice",
      component: () => import("./views/VoiceView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/scanner",
      name: "scanner",
      component: () => import("./views/ScannerView.vue"),
      meta: { requiresAuth: true, requiresSecurity: true },
    },
    {
      path: "/admin",
      component: () => import("./views/AdminView.vue"),
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [
        {
          path: "",
          name: "admin",
          redirect: "/admin/rooms",
        },
        {
          path: "users",
          name: "admin-users",
          component: () => import("./views/AdminUsersView.vue"),
        },
        {
          path: "rooms",
          name: "admin-rooms",
          component: () => import("./views/AdminRoomsView.vue"),
        },
        {
          path: "departments",
          name: "admin-departments",
          component: () => import("./views/AdminDepartmentsView.vue"),
        },
        {
          path: "access-areas",
          name: "admin-access-areas",
          component: () => import("./views/AdminAccessAreasView.vue"),
        },
        {
          path: "users/:uid",
          name: "admin-user-detail",
          component: () => import("./views/UserDetailView.vue"),
          props: true,
        },
      ],
    },
  ],
});

router.beforeEach(async (to) => {
  await waitForAuth();

  const user = auth.currentUser;

  if (to.meta.requiresAuth && !user) {
    return { name: "login" };
  }

  if (to.meta.guest && user) {
    return { name: "voice" };
  }

  if (to.meta.requiresAdmin && user) {
    const snap = await getDoc(doc(db, "users", user.uid));
    const role = snap.data()?.role;
    if (role !== "admin") {
      return { name: "voice" };
    }
  }

  if (to.meta.requiresSecurity && user) {
    const snap = await getDoc(doc(db, "users", user.uid));
    const role = snap.data()?.role;
    if (role !== "security" && role !== "security_admin" && role !== "admin") {
      return { name: "voice" };
    }
  }

  return true;
});

export default router;
