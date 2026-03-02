import { initializeApp } from "firebase/app";
import { getFirestore } from "firebase/firestore";
import { getAuth } from "firebase/auth";
import { getStorage } from "firebase/storage";

const firebaseConfig = {
  apiKey: "AIzaSyAK2WBds2fMXBiMn-CJ__qyN53LU9admuw",
  authDomain: "hot-cue.firebaseapp.com",
  projectId: "hot-cue",
  storageBucket: "hot-cue.firebasestorage.app",
  messagingSenderId: "875796695977",
  appId: "1:875796695977:web:a7dc3a7ed02be5c75ad2b8",
  measurementId: "G-H4CP0X6ENW",
};

const app = initializeApp(firebaseConfig);
const secondaryApp = initializeApp(firebaseConfig, "secondary");

export const db = getFirestore(app);
export const auth = getAuth(app);
export const secondaryAuth = getAuth(secondaryApp);
export const storage = getStorage(app);
