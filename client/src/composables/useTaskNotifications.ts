import { Capacitor } from "@capacitor/core";
import { LocalNotifications } from "@capacitor/local-notifications";
import { collection, onSnapshot } from "firebase/firestore";
import { db } from "../firebase";

type TaskNotificationRole = "room_admin" | "member";

interface NotificationProfile {
  uid: string;
  role: string;
  assignedRoom?: string;
}

let stopTaskListener: (() => void) | null = null;
let activeKey = "";
let initialized = false;
let permissionRequested = false;
let channelReady = false;
let nextNotificationId = 1;
const knownTaskStatuses = new Map<string, string>();
const isNative = Capacitor.isNativePlatform();
const TASK_CHANNEL_ID = "task-alerts";

function resetState() {
  initialized = false;
  knownTaskStatuses.clear();
}

function stop() {
  if (stopTaskListener) {
    stopTaskListener();
    stopTaskListener = null;
  }
  activeKey = "";
  resetState();
}

async function ensureNotificationPermission(): Promise<boolean> {
  if (isNative) {
    try {
      const existing = await LocalNotifications.checkPermissions();
      if (existing.display === "granted") return true;
      if (existing.display === "denied") return false;
      if (permissionRequested) return false;
      permissionRequested = true;
      const requested = await LocalNotifications.requestPermissions();
      return requested.display === "granted";
    } catch {
      return false;
    }
  }

  if (typeof window === "undefined" || typeof Notification === "undefined") return false;
  if (Notification.permission === "granted") return true;
  if (Notification.permission === "denied") return false;
  if (permissionRequested) return false;
  permissionRequested = true;
  try {
    const permission = await Notification.requestPermission();
    return permission === "granted";
  } catch {
    return false;
  }
}

async function ensureTaskChannel() {
  if (!isNative || channelReady) return;
  try {
    await LocalNotifications.createChannel({
      id: TASK_CHANNEL_ID,
      name: "Task Alerts",
      description: "Assignment and completion alerts",
      importance: 5,
      vibration: true,
      visibility: 1,
    });
    channelReady = true;
  } catch {
    // Ignore if channel already exists or channel creation fails.
  }
}

async function showNotification(title: string, body: string) {
  const hasPermission = await ensureNotificationPermission();
  if (!hasPermission) return;

  if (isNative) {
    try {
      await ensureTaskChannel();
      await LocalNotifications.schedule({
        notifications: [
          {
            id: nextNotificationId++,
            title,
            body,
            schedule: { at: new Date(Date.now() + 200) },
            channelId: TASK_CHANNEL_ID,
            sound: "default",
          },
        ],
      });
    } catch {
      // Ignore runtime notification errors on unsupported environments.
    }
    return;
  }

  if (typeof window === "undefined" || typeof Notification === "undefined") return;
  if (Notification.permission !== "granted") return;
  try {
    new Notification(title, { body });
  } catch {
    // Ignore runtime notification errors on unsupported environments.
  }
}

function watchRoomTasks(profile: NotificationProfile, role: TaskNotificationRole, roomId: string) {
  stop();
  activeKey = `${profile.uid}:${role}:${roomId}`;

  const tasksRef = collection(db, "rooms", roomId, "tasks");
  stopTaskListener = onSnapshot(tasksRef, (snap) => {
    if (!initialized) {
      for (const taskDoc of snap.docs) {
        const data = taskDoc.data();
        knownTaskStatuses.set(taskDoc.id, String(data.status || "assigned"));
      }
      initialized = true;
      return;
    }

    for (const change of snap.docChanges()) {
      const taskId = change.doc.id;
      const data = change.doc.data();
      if (change.type === "removed") {
        knownTaskStatuses.delete(taskId);
        continue;
      }

      const nextStatus = String(data.status || "assigned");
      const prevStatus = knownTaskStatuses.get(taskId);
      knownTaskStatuses.set(taskId, nextStatus);

      if (role === "member") {
        const assignedToMe = data.assignedToUid === profile.uid;
        const becameAssigned = assignedToMe && nextStatus === "assigned" && prevStatus !== "assigned";
        if (becameAssigned) {
          const title = "New Task Assigned";
          const body = data.title ? String(data.title) : "You have a new assigned task.";
          void showNotification(title, body);
        }
      } else if (role === "room_admin") {
        const becameCompleted = nextStatus === "completed" && prevStatus !== "completed";
        if (becameCompleted) {
          const completedBy = String(data.completedByName || data.assignedToName || "Member");
          const taskTitle = String(data.title || "Task");
          void showNotification("Task Completed", `${taskTitle} completed by ${completedBy}`);
        }
      }
    }
  });
}

function start(profile: NotificationProfile | null | undefined) {
  if (!profile?.uid) {
    stop();
    return;
  }

  const role = profile.role;
  const roomId = profile.assignedRoom || "";
  if ((role !== "member" && role !== "room_admin") || !roomId) {
    stop();
    return;
  }

  const normalizedRole = role as TaskNotificationRole;
  const nextKey = `${profile.uid}:${normalizedRole}:${roomId}`;
  if (nextKey === activeKey) return;

  void ensureNotificationPermission();
  watchRoomTasks(profile, normalizedRole, roomId);
}

export function useTaskNotifications() {
  return {
    start,
    stop,
  };
}
