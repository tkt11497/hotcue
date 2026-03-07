import { computed, readonly, ref } from "vue";
import { db } from "../firebase";
import {
  addDoc,
  collection,
  doc,
  onSnapshot,
  orderBy,
  query,
  runTransaction,
  serverTimestamp,
  where,
  type Timestamp,
} from "firebase/firestore";

export type RoomTaskStatus = "assigned" | "accepted" | "completed";

export interface RoomTask {
  id: string;
  roomId: string;
  title: string;
  description: string;
  status: RoomTaskStatus;
  assignedToUid: string;
  assignedToName: string;
  assignedByUid: string;
  assignedByName: string;
  completedByUid: string;
  completedByName: string;
  createdAt: Timestamp | null;
  acceptedAt: Timestamp | null;
  completedAt: Timestamp | null;
}

export interface RoomMember {
  uid: string;
  displayName: string;
  email: string;
}

export interface RoomMemberStatus extends RoomMember {
  activeTaskCount: number;
  status: "free" | "busy";
}

interface CurrentActor {
  uid: string;
  displayName: string;
  role: string;
}

const activeRoomId = ref<string | null>(null);
const tasks = ref<RoomTask[]>([]);
const roomMembers = ref<RoomMember[]>([]);
const loadingTasks = ref(false);
const loadingMembers = ref(false);
const actionTaskIds = ref<string[]>([]);
const currentActor = ref<CurrentActor | null>(null);

let stopTaskListener: (() => void) | null = null;
let stopMembersListener: (() => void) | null = null;

function ensureRoomAndActor() {
  if (!activeRoomId.value) throw new Error("No active room selected");
  if (!currentActor.value) throw new Error("Missing current user context");
}

function clearListeners() {
  if (stopTaskListener) {
    stopTaskListener();
    stopTaskListener = null;
  }
  if (stopMembersListener) {
    stopMembersListener();
    stopMembersListener = null;
  }
}

function start(roomId: string, actor: CurrentActor) {
  if (!roomId) return;
  stop();
  activeRoomId.value = roomId;
  currentActor.value = actor;
  loadingTasks.value = true;
  loadingMembers.value = true;

  const taskQuery = query(collection(db, "rooms", roomId, "tasks"), orderBy("createdAt", "desc"));
  stopTaskListener = onSnapshot(
    taskQuery,
    (snap) => {
      tasks.value = snap.docs.map((taskDoc) => {
        const data = taskDoc.data();
        return {
          id: taskDoc.id,
          roomId: data.roomId || roomId,
          title: data.title || "Untitled task",
          description: data.description || "",
          status: (data.status as RoomTaskStatus) || "assigned",
          assignedToUid: data.assignedToUid || "",
          assignedToName: data.assignedToName || "",
          assignedByUid: data.assignedByUid || "",
          assignedByName: data.assignedByName || "",
          completedByUid: data.completedByUid || "",
          completedByName: data.completedByName || "",
          createdAt: data.createdAt || null,
          acceptedAt: data.acceptedAt || null,
          completedAt: data.completedAt || null,
        };
      });
      loadingTasks.value = false;
    },
    () => {
      loadingTasks.value = false;
    }
  );

  const memberQuery = query(collection(db, "users"), where("assignedRoom", "==", roomId));
  stopMembersListener = onSnapshot(
    memberQuery,
    (snap) => {
      roomMembers.value = snap.docs
        .map((userDoc) => {
          const data = userDoc.data();
          return {
            uid: userDoc.id,
            displayName: data.displayName || userDoc.id,
            email: data.email || "",
            role: data.role || "",
          };
        })
        .filter((user) => user.role === "member")
        .map(({ uid, displayName, email }) => ({ uid, displayName, email }));
      loadingMembers.value = false;
    },
    () => {
      loadingMembers.value = false;
    }
  );
}

function stop() {
  clearListeners();
  activeRoomId.value = null;
  currentActor.value = null;
  tasks.value = [];
  roomMembers.value = [];
  loadingTasks.value = false;
  loadingMembers.value = false;
  actionTaskIds.value = [];
}

async function assignTask(input: { title: string; description?: string; assignedToUid: string; assignedToName: string }) {
  ensureRoomAndActor();
  if (currentActor.value?.role !== "room_admin") {
    throw new Error("Only room admins can assign tasks");
  }
  const title = input.title.trim();
  if (!title) throw new Error("Task title is required");
  if (!input.assignedToUid) throw new Error("Task assignee is required");
  const assignee = roomMembers.value.find((member) => member.uid === input.assignedToUid);
  if (!assignee) {
    throw new Error("Selected user is not a member of this assigned room");
  }
  const hasActiveTask = tasks.value.some(
    (task) => task.assignedToUid === input.assignedToUid && task.status !== "completed"
  );
  if (hasActiveTask) {
    throw new Error("This member already has an active task");
  }

  await addDoc(collection(db, "rooms", activeRoomId.value!, "tasks"), {
    roomId: activeRoomId.value,
    title,
    description: (input.description || "").trim(),
    status: "assigned",
    assignedToUid: input.assignedToUid,
    assignedToName: input.assignedToName || input.assignedToUid,
    assignedByUid: currentActor.value.uid,
    assignedByName: currentActor.value.displayName,
    completedByUid: null,
    completedByName: null,
    createdAt: serverTimestamp(),
    acceptedAt: null,
    completedAt: null,
  });
}

async function acceptTask(taskId: string) {
  ensureRoomAndActor();
  if (!taskId) return;
  if (currentActor.value?.role !== "member") {
    throw new Error("Only room members can accept tasks");
  }
  actionTaskIds.value = [...actionTaskIds.value, taskId];
  try {
    const taskRef = doc(db, "rooms", activeRoomId.value!, "tasks", taskId);
    await runTransaction(db, async (tx) => {
      const snap = await tx.get(taskRef);
      if (!snap.exists()) throw new Error("Task not found");
      const data = snap.data();
      if (data.assignedToUid !== currentActor.value?.uid) {
        throw new Error("Only assigned member can accept this task");
      }
      if (data.status !== "assigned") {
        throw new Error("Only assigned tasks can be accepted");
      }
      tx.update(taskRef, {
        status: "accepted",
        acceptedAt: serverTimestamp(),
      });
    });
  } finally {
    actionTaskIds.value = actionTaskIds.value.filter((id) => id !== taskId);
  }
}

async function completeTask(taskId: string) {
  ensureRoomAndActor();
  if (!taskId) return;
  if (currentActor.value?.role !== "member") {
    throw new Error("Only room members can complete tasks");
  }
  actionTaskIds.value = [...actionTaskIds.value, taskId];
  try {
    const taskRef = doc(db, "rooms", activeRoomId.value!, "tasks", taskId);
    await runTransaction(db, async (tx) => {
      const snap = await tx.get(taskRef);
      if (!snap.exists()) throw new Error("Task not found");
      const data = snap.data();
      if (data.assignedToUid !== currentActor.value?.uid) {
        throw new Error("Only assigned member can complete this task");
      }
      if (data.status !== "accepted") {
        throw new Error("Only accepted tasks can be completed");
      }
      tx.update(taskRef, {
        status: "completed",
        completedAt: serverTimestamp(),
        completedByUid: currentActor.value?.uid || "",
        completedByName: currentActor.value?.displayName || "",
      });
    });
  } finally {
    actionTaskIds.value = actionTaskIds.value.filter((id) => id !== taskId);
  }
}

const activeTaskCountsByUser = computed(() => {
  const counts = new Map<string, number>();
  for (const task of tasks.value) {
    if (task.status === "completed") continue;
    counts.set(task.assignedToUid, (counts.get(task.assignedToUid) || 0) + 1);
  }
  return counts;
});

const memberStatus = computed<RoomMemberStatus[]>(() =>
  roomMembers.value.map((member) => {
    const activeTaskCount = activeTaskCountsByUser.value.get(member.uid) || 0;
    return {
      ...member,
      activeTaskCount,
      status: activeTaskCount > 0 ? "busy" : "free",
    };
  })
);

function tasksForMember(uid: string) {
  return tasks.value.filter((task) => task.assignedToUid === uid);
}

export function useRoomTasks() {
  return {
    roomId: readonly(activeRoomId),
    tasks: readonly(tasks),
    roomMembers: readonly(roomMembers),
    loadingTasks: readonly(loadingTasks),
    loadingMembers: readonly(loadingMembers),
    actionTaskIds: readonly(actionTaskIds),
    activeTaskCountsByUser,
    memberStatus,
    tasksForMember,
    start,
    stop,
    assignTask,
    acceptTask,
    completeTask,
  };
}
