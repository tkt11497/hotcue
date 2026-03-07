<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { doc, getDoc } from "firebase/firestore";
import { db } from "../firebase";
import { useAuth } from "../composables/useAuth";
import { useRoomTasks } from "../composables/useRoomTasks";

const { userProfile } = useAuth();
const roomTasks = useRoomTasks();

const taskError = ref<string | null>(null);
const roomName = ref("");

const isRoomAdmin = computed(() => userProfile.value?.role === "room_admin");
const isMember = computed(() => userProfile.value?.role === "member");
const canUseTaskBoard = computed(() => isRoomAdmin.value || isMember.value);
const assignedRoomId = computed(() => userProfile.value?.assignedRoom || "");

const newTaskTitle = ref("");
const newTaskDescription = ref("");
const newTaskAssignee = ref("");

const assignableMembers = computed(() =>
  roomTasks.memberStatus.value.filter((member) => member.activeTaskCount === 0)
);
const activeTasks = computed(() => roomTasks.tasks.value.filter((task) => task.status !== "completed"));
const completedTasks = computed(() => roomTasks.tasks.value.filter((task) => task.status === "completed"));
const myTasks = computed(() =>
  roomTasks.tasks.value.filter((task) => task.assignedToUid === (userProfile.value?.uid || ""))
);
const myAssignedTasks = computed(() => myTasks.value.filter((task) => task.status === "assigned"));
const myAcceptedTasks = computed(() => myTasks.value.filter((task) => task.status === "accepted"));
const myCompletedTasks = computed(() => myTasks.value.filter((task) => task.status === "completed"));

function isTaskActionPending(taskId: string) {
  return roomTasks.actionTaskIds.value.includes(taskId);
}

function formatTaskTimestamp(value: unknown): string {
  if (!value || typeof value !== "object") return "—";
  const maybeTimestamp = value as { toDate?: () => Date };
  if (typeof maybeTimestamp.toDate !== "function") return "—";
  const date = maybeTimestamp.toDate();
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
    hour12: true,
  });
}

watch(
  () =>
    [
      userProfile.value?.uid,
      userProfile.value?.displayName,
      userProfile.value?.role,
      assignedRoomId.value,
    ] as const,
  async ([uid, displayName, role, roomId]) => {
    taskError.value = null;
    roomName.value = "";
    if (!uid || !displayName || !role || !roomId || (role !== "room_admin" && role !== "member")) {
      roomTasks.stop();
      return;
    }
    roomTasks.start(roomId, { uid, displayName, role });
    const roomSnap = await getDoc(doc(db, "rooms", roomId));
    roomName.value = roomSnap.data()?.name || roomId;
  },
  { immediate: true }
);

onUnmounted(() => {
  roomTasks.stop();
});

async function submitTaskAssignment() {
  const title = newTaskTitle.value.trim();
  if (!title || !newTaskAssignee.value) return;
  const assignee = assignableMembers.value.find((member) => member.uid === newTaskAssignee.value);
  if (!assignee) return;
  try {
    taskError.value = null;
    await roomTasks.assignTask({
      title,
      description: newTaskDescription.value.trim(),
      assignedToUid: assignee.uid,
      assignedToName: assignee.displayName || assignee.uid,
    });
    newTaskTitle.value = "";
    newTaskDescription.value = "";
    newTaskAssignee.value = "";
  } catch (err: any) {
    taskError.value = err?.message || "Failed to assign task";
  }
}

async function handleAcceptTask(taskId: string) {
  try {
    taskError.value = null;
    await roomTasks.acceptTask(taskId);
  } catch (err: any) {
    taskError.value = err?.message || "Failed to accept task";
  }
}

async function handleCompleteTask(taskId: string) {
  try {
    taskError.value = null;
    await roomTasks.completeTask(taskId);
  } catch (err: any) {
    taskError.value = err?.message || "Failed to complete task";
  }
}
</script>

<template>
  <div class="task-page">
    <div class="task-header">
      <h2>Task Board</h2>
      <p class="task-subtitle">
        <template v-if="canUseTaskBoard && assignedRoomId">
          Room: <strong>{{ roomName || assignedRoomId }}</strong>
        </template>
        <template v-else>
          Task board is available for Room Admin and Member users.
        </template>
      </p>
    </div>

    <div v-if="!canUseTaskBoard" class="task-card">
      <p class="task-muted">Your role does not have access to task board.</p>
    </div>

    <div v-else-if="!assignedRoomId" class="task-card">
      <p class="task-muted">No assigned room found for your account. Ask admin to set your assigned room.</p>
    </div>

    <div v-else class="task-card">
      <div class="task-panel-header">
        <h3>{{ isRoomAdmin ? "Room Admin Tasks" : "My Tasks" }}</h3>
        <span class="task-live">Realtime</span>
      </div>
      <p v-if="taskError" class="task-error">{{ taskError }}</p>

      <template v-if="isRoomAdmin">
        <form class="task-form" @submit.prevent="submitTaskAssignment">
          <input
            v-model="newTaskTitle"
            class="task-input"
            type="text"
            placeholder="Task title"
            maxlength="120"
            required
          />
          <textarea
            v-model="newTaskDescription"
            class="task-input task-textarea"
            placeholder="Optional details"
            rows="2"
            maxlength="300"
          />
          <div class="task-form-row">
            <select v-model="newTaskAssignee" class="task-input" required>
              <option value="" disabled>Select member from this room...</option>
              <option v-for="member in assignableMembers" :key="member.uid" :value="member.uid">
                {{ member.displayName }} ({{ member.status }})
              </option>
            </select>
            <button class="btn-task" type="submit" :disabled="!newTaskTitle.trim() || !newTaskAssignee">
              Assign
            </button>
          </div>
        </form>

        <div class="task-grid">
          <div class="task-section">
            <h4>Member Status</h4>
            <div v-if="roomTasks.loadingMembers.value" class="task-muted">Loading members...</div>
            <div v-else-if="roomTasks.memberStatus.value.length === 0" class="task-muted">No assigned members in this room.</div>
            <div v-else class="member-list">
              <div v-for="member in roomTasks.memberStatus.value" :key="member.uid" class="member-row">
                <div>
                  <div class="member-name">{{ member.displayName }}</div>
                  <div class="member-email">{{ member.email || member.uid }}</div>
                </div>
                <div class="member-state" :class="member.status">
                  <span>{{ member.status }}</span>
                  <small>{{ member.activeTaskCount }} active</small>
                </div>
              </div>
            </div>
          </div>

          <div class="task-section">
            <h4>Active Tasks</h4>
            <div v-if="roomTasks.loadingTasks.value" class="task-muted">Loading tasks...</div>
            <div v-else-if="activeTasks.length === 0" class="task-muted">No active tasks.</div>
            <div v-else class="task-list">
              <div v-for="task in activeTasks" :key="task.id" class="task-card-inner">
                <div class="task-row">
                  <strong>{{ task.title }}</strong>
                  <span class="task-status" :class="task.status">{{ task.status }}</span>
                </div>
                <div class="task-meta">{{ task.assignedToName }} · by {{ task.assignedByName }}</div>
                <p v-if="task.description" class="task-desc">{{ task.description }}</p>
              </div>
            </div>
          </div>

          <div class="task-section">
            <h4>Completed Tasks</h4>
            <div v-if="roomTasks.loadingTasks.value" class="task-muted">Loading tasks...</div>
            <div v-else-if="completedTasks.length === 0" class="task-muted">No completed tasks yet.</div>
            <div v-else class="task-list">
              <div v-for="task in completedTasks" :key="task.id" class="task-card-inner done">
                <div class="task-row">
                  <strong>{{ task.title }}</strong>
                  <span class="task-status completed">completed</span>
                </div>
                <div class="task-meta">{{ task.assignedToName }} · assigned by {{ task.assignedByName }}</div>
                <div class="task-meta">Completed by {{ task.completedByName || task.assignedToName || "Unknown" }}</div>
                <div class="task-meta">Completed at {{ formatTaskTimestamp(task.completedAt) }}</div>
                <p v-if="task.description" class="task-desc">{{ task.description }}</p>
              </div>
            </div>
          </div>
        </div>
      </template>

      <template v-else-if="isMember">
        <div v-if="roomTasks.loadingTasks.value" class="task-muted">Loading tasks...</div>
        <div v-else class="task-grid member-grid">
          <div class="task-section">
            <h4>Assigned</h4>
            <div v-if="myAssignedTasks.length === 0" class="task-muted">No new assigned tasks.</div>
            <div v-else class="task-list">
              <div v-for="task in myAssignedTasks" :key="task.id" class="task-card-inner">
                <div class="task-row">
                  <strong>{{ task.title }}</strong>
                  <span class="task-status assigned">assigned</span>
                </div>
                <p v-if="task.description" class="task-desc">{{ task.description }}</p>
                <button
                  class="btn-task"
                  type="button"
                  :disabled="isTaskActionPending(task.id)"
                  @click="handleAcceptTask(task.id)"
                >
                  {{ isTaskActionPending(task.id) ? "Accepting..." : "Accept" }}
                </button>
              </div>
            </div>
          </div>

          <div class="task-section">
            <h4>In Progress</h4>
            <div v-if="myAcceptedTasks.length === 0" class="task-muted">No accepted tasks.</div>
            <div v-else class="task-list">
              <div v-for="task in myAcceptedTasks" :key="task.id" class="task-card-inner">
                <div class="task-row">
                  <strong>{{ task.title }}</strong>
                  <span class="task-status accepted">accepted</span>
                </div>
                <p v-if="task.description" class="task-desc">{{ task.description }}</p>
                <button
                  class="btn-task"
                  type="button"
                  :disabled="isTaskActionPending(task.id)"
                  @click="handleCompleteTask(task.id)"
                >
                  {{ isTaskActionPending(task.id) ? "Completing..." : "Complete" }}
                </button>
              </div>
            </div>
          </div>

          <div class="task-section">
            <h4>Completed</h4>
            <div v-if="myCompletedTasks.length === 0" class="task-muted">No completed tasks yet.</div>
            <div v-else class="task-list">
              <div v-for="task in myCompletedTasks" :key="task.id" class="task-card-inner done">
                <div class="task-row">
                  <strong>{{ task.title }}</strong>
                  <span class="task-status completed">completed</span>
                </div>
                <p v-if="task.description" class="task-desc">{{ task.description }}</p>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.task-page {
  width: 100%;
  max-width: 980px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.task-header h2 {
  font-size: 1.25rem;
}

.task-subtitle {
  margin-top: 4px;
  color: var(--text-muted);
}

.task-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
}

.task-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.task-panel-header h3 {
  font-size: 1rem;
}

.task-live {
  font-size: 0.72rem;
  color: var(--text-muted);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 8px;
}

.task-error {
  margin-top: 10px;
  color: var(--danger);
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  font-size: 0.82rem;
}

.task-form {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-form-row {
  display: flex;
  gap: 8px;
}

.task-input {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg);
  color: var(--text);
  padding: 8px 10px;
  font-size: 0.85rem;
}

.task-textarea {
  resize: vertical;
}

.btn-task {
  border: none;
  border-radius: var(--radius-sm);
  background: var(--primary);
  color: #fff;
  font-size: 0.82rem;
  font-weight: 600;
  padding: 8px 12px;
  cursor: pointer;
  white-space: nowrap;
}

.btn-task:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.task-grid {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.task-section {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg);
  padding: 10px;
}

.task-section h4 {
  font-size: 0.83rem;
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  color: var(--text-muted);
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-card-inner {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-card-inner.done {
  opacity: 0.75;
}

.task-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.task-status {
  font-size: 0.68rem;
  font-weight: 700;
  text-transform: uppercase;
  border-radius: 999px;
  padding: 2px 7px;
}

.task-status.assigned {
  color: #f39c12;
  background: rgba(243, 156, 18, 0.15);
}

.task-status.accepted {
  color: #3498db;
  background: rgba(52, 152, 219, 0.15);
}

.task-status.completed {
  color: var(--success);
  background: rgba(46, 204, 113, 0.15);
}

.task-meta {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.task-desc {
  font-size: 0.8rem;
  color: var(--text);
  margin: 0;
}

.task-muted {
  font-size: 0.8rem;
  color: var(--text-muted);
}

.member-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.member-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px;
  background: var(--surface);
}

.member-name {
  font-size: 0.86rem;
  font-weight: 600;
}

.member-email {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.member-state {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  font-size: 0.72rem;
  font-weight: 700;
  text-transform: uppercase;
}

.member-state small {
  text-transform: none;
  font-weight: 500;
}

.member-state.free {
  color: var(--success);
}

.member-state.busy {
  color: #f39c12;
}
</style>
