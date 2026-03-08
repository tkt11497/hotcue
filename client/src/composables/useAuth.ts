import { ref, readonly, computed } from "vue";
import { auth, secondaryAuth, db } from "../firebase";
import {
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  type User,
} from "firebase/auth";
import { doc, getDoc, setDoc, serverTimestamp } from "firebase/firestore";

export type UserRole = "admin" | "holding_admin" | "room_admin" | "security_admin" | "security" | "member";

export interface UserProfile {
  uid: string;
  email: string;
  displayName: string;
  role: UserRole;
  roleDescription?: string;
  department?: string;
  assignedRoom?: string;
  accessAreas?: string[];
  company?: string;
  photoURL?: string;
}

const user = ref<User | null>(null);
const userProfile = ref<UserProfile | null>(null);
const authReady = ref(false);

let initialized = false;

function initAuthListener() {
  if (initialized) return;
  initialized = true;

  onAuthStateChanged(auth, async (firebaseUser) => {
    user.value = firebaseUser;
    if (firebaseUser) {
      const snap = await getDoc(doc(db, "users", firebaseUser.uid));
      if (snap.exists()) {
        const data = snap.data();
        userProfile.value = {
          uid: firebaseUser.uid,
          email: data.email,
          displayName: data.displayName,
          role: data.role ?? "member",
          roleDescription: data.roleDescription ?? undefined,
          department: data.department ?? undefined,
          assignedRoom: data.assignedRoom ?? undefined,
          accessAreas: data.accessAreas ?? undefined,
          company: data.company ?? undefined,
          photoURL: data.photoURL ?? undefined,
        };
      } else {
        userProfile.value = null;
      }
    } else {
      userProfile.value = null;
    }
    authReady.value = true;
  });
}

export function useAuth() {
  initAuthListener();

  const isAdmin = computed(() => userProfile.value?.role === "admin");
  const isHoldingAdmin = computed(() => userProfile.value?.role === "holding_admin");
  const isRoomAdmin = computed(() => userProfile.value?.role === "room_admin");
  const isSecurityAdmin = computed(() => userProfile.value?.role === "security_admin");
  const isSecurity = computed(() => userProfile.value?.role === "security");
  const isSecurityRole = computed(
    () => userProfile.value?.role === "security_admin" || userProfile.value?.role === "security"
  );
  const canAccessAllRooms = computed(
    () => userProfile.value?.role === "admin" || userProfile.value?.role === "holding_admin"
  );

  async function login(email: string, password: string) {
    await signInWithEmailAndPassword(auth, email, password);
  }

  async function createUser(opts: {
    email: string;
    password: string;
    displayName: string;
    role?: UserRole;
    roleDescription?: string;
    department?: string;
    assignedRoom?: string;
    accessAreas?: string[];
    company?: string;
    photoURL?: string;
  }) {
    const cred = await createUserWithEmailAndPassword(secondaryAuth, opts.email, opts.password);
    await signOut(secondaryAuth);
    const userData: Record<string, any> = {
      email: opts.email,
      displayName: opts.displayName,
      role: opts.role ?? "member",
      createdAt: serverTimestamp(),
    };
    if (opts.roleDescription?.trim()) userData.roleDescription = opts.roleDescription.trim();
    if (opts.department) userData.department = opts.department;
    if (opts.assignedRoom) userData.assignedRoom = opts.assignedRoom;
    if (opts.accessAreas?.length) userData.accessAreas = opts.accessAreas;
    if (opts.company) userData.company = opts.company;
    if (opts.photoURL) userData.photoURL = opts.photoURL;
    await setDoc(doc(db, "users", cred.user.uid), userData);
    return cred.user.uid;
  }

  async function logout() {
    await signOut(auth);
  }

  return {
    user: readonly(user),
    userProfile: readonly(userProfile),
    authReady: readonly(authReady),
    isAdmin,
    isHoldingAdmin,
    isRoomAdmin,
    isSecurityAdmin,
    isSecurity,
    isSecurityRole,
    canAccessAllRooms,
    login,
    createUser,
    logout,
  };
}
