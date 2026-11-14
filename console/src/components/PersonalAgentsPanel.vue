<template>
  <section class="personal-panel">
    <aside class="personal-directory">
      <div class="personal-heading">
        <h2>Agents</h2>
        <el-button size="small" @click="createOpen = true">New Agent</el-button>
      </div>
      <div class="personal-agent-select">
        <el-select :model-value="selectedId" aria-label="Agent" placeholder="Choose an Agent" :disabled="busy" filterable @change="chooseAgent">
          <el-option v-for="agent in orderedAgents" :key="agent.id" :value="agent.id" :label="agent.name">
            <span>{{ agent.name }}</span><span class="agent-option-state">{{ agent.id === defaults.primaryAgentId ? 'Primary · ' : '' }}{{ agent.enabled ? 'Active' : 'Paused' }}</span>
          </el-option>
        </el-select>
      </div>
      <el-button v-if="defaults.primaryAgentId && selectedId !== defaults.primaryAgentId"
        class="primary-agent-link" text @click="chooseAgent(defaults.primaryAgentId)">Back to primary Agent</el-button>
      <div v-if="selected" class="personal-conversations">
        <el-button :disabled="busy" @click="newChat">New chat</el-button>
        <div
          v-for="conversation in conversations"
          :key="conversation.sessionId"
          :class="[
            'session-item',
            { active: sessionId === conversation.sessionId },
          ]"
        >
          <button
            class="session-main"
            @click="
              tab = 'chat';
              openAgentConversation(conversation.sessionId);
            "
          >
            <span>{{ conversation.title }}</span
            ><small v-if="conversation.running">Running</small>
          </button>
          <ChatActions
            :busy="busy || conversations.some((c) => c.running)"
            @rename="renameChat(conversation)"
            @remove="removeChat(conversation)"
          />
        </div>
        <el-empty
          v-if="!conversations.length"
          description="Start a new chat."
          :image-size="50"
        />
      </div>
      <el-empty
        v-if="!agents.length && !loading"
        description="Create your first assistant."
        :image-size="70"
      />
    </aside>
    <main class="personal-detail">
      <p v-if="error" class="personal-error" role="alert">{{ error }}</p>
      <el-empty
        v-if="!selected"
        description="Choose an Agent or create one to get started."
      />
      <template v-else>
        <div class="personal-heading">
          <div>
            <h2>{{ selected.name }} <el-tag v-if="selectedId === defaults.primaryAgentId" size="small" effect="plain">Primary Agent</el-tag></h2>
          </div>
          <el-button :loading="busy" @click="togglePause">{{
            selected.enabled ? "Pause Agent" : "Resume Agent"
          }}</el-button>
        </div>
        <el-tabs
          v-model="tab"
          class="personal-tabs"
          :before-leave="beforeTabLeave"
        >
          <el-tab-pane label="Chat" name="chat">
            <div class="personal-chat-layout">
              <ChatPanel
                v-model:draft="draft"
                v-model:model-id="modelId"
                :draft-images="draftImages"
                :adding-images="addingImages"
                :image-error="imageError"
                @add-images="addImages"
                @remove-image="id => draftImages = draftImages.filter(image => image.imageId !== id)"
                :current-session="activeConversation"
                :messages="displayMessages"
                :status-text="chatStatus"
                :running="agentRunning"
                :stopping="false"
                :stop-ready="false"
                :allow-stop="false"
                :allow-queue="true"
                :allow-images="true"
                :show-model-status="false"
                :composer-rows="2"
                :disabled="!selected.enabled"
                :submitting-input="busy"
                :message-revision="chatRevision + inboxRevision"
                :context-usage="chatUsage"
                placeholder="Tell your Agent what you want to accomplish…"
                @send="send"
              />
            </div>
            <div
              v-for="message in messages.filter((m) => m.deliveryError)"
              :key="message.id"
              class="personal-error"
            >
              {{ message.deliveryError }}
              <el-button size="small" @click="retryDelivery(message)"
                >Retry delivery</el-button
              >
            </div>
          </el-tab-pane>
          <el-tab-pane label="Projects" name="projects">
            <p class="personal-muted">
              Projects created by {{ selected.name }}. Open a conversation to
              inspect progress or help with the work.
            </p>
            <el-empty
              v-if="!projectGroups.length"
              description="Describe a problem in Chat. Your Agent can create a project when it needs one."
              :image-size="70"
            />
            <article
              v-for="project in projectGroups"
              :key="project.id"
              class="personal-card"
            >
              <h3>{{ project.name }}</h3>
              <small>{{ project.workspace }}</small>
              <div v-for="session in project.sessions" :key="session.sessionId">
                <el-button
                  link
                  type="primary"
                  @click="openProject(session.sessionId)"
                  >{{ session.title }}</el-button
                >
              </div>
            </article>
          </el-tab-pane>
          <el-tab-pane label="Tasks" name="board">
            <AgentTasksPanel
              :agent-id="selectedId"
              :tasks="cards"
              :projects="projectSessions"
              :conversations="conversations"
              :active="tab === 'board'"
              @open-project="openProject"
              @open-chat="openTaskConversation"
              @open-delegation="openDelegation"
            />
          </el-tab-pane>
          <el-tab-pane label="Knowledge" name="learning">
            <el-tabs v-model="knowledgeTab">
              <el-tab-pane label="Memory" name="memory">
                <el-form
                  v-if="profile"
                  label-position="top"
                  class="personal-settings"
                >
                  <el-form-item label="Persistent memory"
                    ><el-input
                      v-model="profile.memory"
                      type="textarea"
                      :autosize="{ minRows: 5, maxRows: 16 }"
                      placeholder="Preferences and lessons this Agent should remember"
                      show-word-limit
                      maxlength="24000"
                  /></el-form-item>
                  <p class="personal-muted">
                    Memory is included in future conversations. The Agent can
                    update it as it learns; concurrent edits require reloading.
                  </p>
                  <el-button type="primary" :loading="busy" @click="saveProfile"
                    >Save memory</el-button
                  >
                  <el-button @click="reloadProfile"
                    >Reload saved version</el-button
                  >
                </el-form>
              </el-tab-pane>
              <el-tab-pane label="Skills & learning" name="skills">
                <AgentLearningPanel
                  ref="learningPanelRef"
                  v-if="tab === 'learning'"
                  :key="selectedId"
                  :agent-id="selectedId"
                  :conversation-id="sessionId"
                  :enabled="selected.enabled"
                  :revision="selected.revision"
                  @saved="knowledgeSaved"
                  @open-project="openProject($event)"
                  @open-conversation="openAgentConversation"
                />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
          <el-tab-pane label="Configuration" name="settings">
            <p class="personal-muted">
              Configuration for {{ selected.name }}. Changes affect subsequent
              model calls; existing projects retain their own settings.
            </p>
            <el-tabs v-model="configTab" :before-leave="beforeConfigTabLeave">
              <el-tab-pane label="General & instructions" name="general">
                <div class="primary-agent-setting">
                  <div><strong>{{ selectedId === defaults.primaryAgentId ? 'Your primary Agent' : 'Primary Agent' }}</strong>
                    <p class="personal-muted">The default chat entry point. Its bound Runner is the default for Agents you create. Existing Agents and projects keep their bindings.</p></div>
                  <el-button v-if="selectedId !== defaults.primaryAgentId" :disabled="!selected.runnerId" :loading="busy" @click="makePrimary">Set as primary</el-button>
                  <el-tag v-else effect="plain">Selected</el-tag>
                </div>
                <el-form
                  v-if="profile"
                  label-position="top"
                  class="personal-settings"
                  @submit.prevent="saveProfile"
                >
                  <el-form-item label="Name"
                    ><el-input v-model="profile.name" maxlength="128"
                  /></el-form-item>
                  <el-form-item label="Purpose"><el-input v-model="profile.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" maxlength="4000" placeholder="What other Agents should ask this Agent to do" /></el-form-item>
                  <el-form-item label="Instructions"
                    ><el-input
                      v-model="profile.instructions"
                      type="textarea"
                      :autosize="{ minRows: 3, maxRows: 12 }"
                      placeholder="Role, responsibilities, preferences, and boundaries"
                      maxlength="16000"
                  /></el-form-item>
                  <el-form-item label="Bound Runner">
                    <select v-model="profile.runnerId" :disabled="!!selected.runnerId">

                      <option
                        v-for="runner in runners"
                        :key="runner.runnerId"
                        :value="runner.runnerId"
                      >
                        {{ runner.name }} ·
                        {{ runner.online ? "Online" : "Offline" }}
                      </option>
                    </select>
                  </el-form-item>
                  <p class="personal-muted">
                    Each new project gets its own directory under this Agent's
                    default workspace on the Runner.
                    <code v-if="defaultWorkspace">{{ defaultWorkspace }}</code>
                  </p>
                  <el-button type="primary" :loading="busy" @click="saveProfile"
                    >Save</el-button
                  ><el-button @click="reloadProfile"
                    >Reload saved version</el-button
                  >
                </el-form>
              </el-tab-pane>
              <el-tab-pane label="Tools" name="tools">
                <p class="personal-muted">
                  These tools run on the server for this Agent. The default
                  Runner is used by its projects, not directly by this chat.
                  Other available server and MCP tools are listed in Runtime
                  Context.
                </p>
                <el-button type="primary" :loading="busy" @click="saveProfile"
                  >Save tool selection</el-button
                >
                <div
                  v-for="tool in agentTools"
                  :key="tool.name"
                  class="personal-tool"
                >
                  <el-checkbox
                    :model-value="!profile?.disabledTools?.includes(tool.name)"
                    @change="toggleTool(tool.name, $event)"
                    ><code>{{ tool.name }}</code></el-checkbox
                  >
                  <p>{{ tool.description }}</p>
                  <div class="personal-tool-actions">
                    <el-checkbox v-for="action in tool.actions" :key="action.name"
                      :disabled="profile?.disabledTools?.includes(tool.name)"
                      :model-value="toolActionEnabled(tool.name, action)"
                      :title="action.description"
                      @change="toggleToolAction(tool.name, action, $event)">
                      {{ action.name }}
                    </el-checkbox>
                  </div>
                  <details>
                    <summary>Input schema</summary>
                    <pre>{{
                      JSON.stringify(tool.parametersSchema, null, 2)
                    }}</pre>
                  </details>
                </div>
              </el-tab-pane>
              <el-tab-pane label="Skills" name="skills">
                <AgentLearningPanel
                  v-if="tab === 'settings' && configTab === 'skills'"
                  ref="configurationSkillsRef"
                  :key="selectedId"
                  :agent-id="selectedId"
                  :revision="selected.revision"
                  skills-only
                  @saved="knowledgeSaved"
                />
              </el-tab-pane>
              <el-tab-pane label="Runtime Context" name="runtime">
                <p class="personal-muted">
                  Generated from current settings for the selected chat,
                  including Agent instructions and memory. This is not a past
                  request snapshot.
                </p>
                <RuntimeContextPanel
                  v-if="sessionId && configTab === 'runtime'"
                  :session-id="sessionId"
                  :title="activeConversation?.title"
                  :key="selected.revision"
                />
                <el-empty
                  v-else-if="!sessionId"
                  description="Create a chat to preview its runtime context."
                />
              </el-tab-pane>
              <el-tab-pane label="Channels" name="feishu">
                <div class="personal-settings">
                  <h3>Talk to this Agent from Feishu</h3>
                  <p>
                    Optional. Your Agent, memory, and projects work
                    independently of this connection.
                  </p>
                  <template v-if="feishu.available">
                    <p>
                      <el-tag>{{ feishu.status }}</el-tag>
                      <span v-if="feishu.appId">{{ feishu.appId }}</span>
                    </p>
                    <p v-if="feishu.error" class="personal-error">
                      {{ feishu.error }}
                    </p>
                    <div v-if="qrImage" class="personal-qr">
                      <img
                        :src="qrImage"
                        alt="Scan with Feishu to authorize this Agent"
                        width="220"
                        height="220"
                      /><a
                        :href="feishu.authorizationUrl"
                        target="_blank"
                        rel="noopener noreferrer"
                        >Open authorization page</a
                      ><small
                        >Confirm the app and permissions in Feishu. Tenant
                        approval may be required.</small
                      >
                    </div>
                    <p v-if="feishu.pairCode">
                      Send <code>{{ feishu.pairCode }}</code> in a direct
                      message to the bot within ten minutes to pair your
                      account. Only that account can use this connection.
                    </p>
                    <el-button
                      v-if="!feishu.configured"
                      type="primary"
                      :loading="busy"
                      @click="registerFeishu"
                      >{{
                        feishu.status === "DISCONNECTED"
                          ? "Connect with Feishu"
                          : "Restart authorization"
                      }}</el-button
                    >
                    <el-button
                      v-if="
                        feishu.configured &&
                        ['DISCONNECTED', 'ERROR'].includes(feishu.status)
                      "
                      type="primary"
                      :loading="busy"
                      @click="reconnectFeishu"
                      >{{
                        feishu.status === "ERROR"
                          ? "Retry connection"
                          : "Reconnect"
                      }}</el-button
                    >
                    <el-button
                      v-if="feishu.status !== 'DISCONNECTED'"
                      :loading="busy"
                      @click="disconnectFeishu"
                      >Disconnect</el-button
                    >
                    <p v-if="feishu.configured" class="personal-muted">
                      Disconnect pauses messaging and keeps this bot's
                      credentials, paired account, and conversation. Reconnect
                      resumes the same bot without another QR authorization.
                    </p>
                    <details class="personal-manual">
                      <summary>
                        {{
                          feishu.configured
                            ? "Use another app"
                            : "Use an existing app"
                        }}
                      </summary>
                      <el-button
                        v-if="feishu.configured"
                        :loading="busy"
                        @click="registerFeishu"
                        >Create a new bot with Feishu</el-button
                      >
                      <p>
                        Enable the bot, subscribe to
                        <code>im.message.receive_v1</code> using a long
                        connection, grant
                        <code>im:message:send_as_bot</code> and
                        <code>im:message.p2p_msg:readonly</code>, then publish
                        the app. No public webhook address is needed.
                      </p>
                      <el-form
                        label-position="top"
                        @submit.prevent="connectFeishu"
                        ><el-form-item label="App ID"
                          ><el-input
                            v-model="credentials.appId"
                            autocomplete="off" /></el-form-item
                        ><el-form-item label="App Secret"
                          ><el-input
                            v-model="credentials.appSecret"
                            type="password"
                            autocomplete="new-password"
                            show-password /></el-form-item
                        ><el-form-item label="Platform"
                          ><select v-model="credentials.brand">
                            <option value="feishu">Feishu</option>
                            <option value="lark">Lark</option>
                          </select></el-form-item
                        ><el-button :loading="busy" @click="connectFeishu"
                          >Connect existing app</el-button
                        ></el-form
                      >
                    </details>
                    <p class="personal-muted">
                      Text direct messages are supported. Credentials stay in
                      server storage and are never passed to the model. Replies
                      and meaningful progress updates are also visible in the
                      console.
                    </p>
                  </template>
                  <p v-else>
                    This server does not have the optional Feishu integration
                    enabled.
                  </p>
                </div>
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>
      </template>
    </main>
    <RenameDialog
      :open="!!renaming"
      v-model="renameTitle"
      title="Rename chat"
      label="Title"
      :submitting="busy"
      :error="error"
      @submit="saveChatTitle"
      @close="renaming = null"
    />
    <el-dialog
      v-model="createOpen"
      title="New personal Agent"
      width="min(520px, 94vw)"
    >
      <el-form label-position="top"
        ><el-form-item label="Name"
          ><el-input
            v-model="newProfile.name"
            maxlength="128"
            placeholder="Developer" /></el-form-item
        ><el-form-item label="Purpose (optional)"><el-input v-model="newProfile.description" type="textarea" :rows="2" maxlength="4000" placeholder="What this Agent helps with; visible to other Agents" /></el-form-item><el-form-item label="Bound Runner">
          <el-select
            v-model="newProfile.runnerId"
            placeholder="Choose a Runner"
            style="width: 100%"
          >
            <el-option
              v-for="runner in runners"
              :key="runner.runnerId"
              :value="runner.runnerId"
              :label="
                runner.name + (runner.online ? ' · Online' : ' · Offline')
              "
              :title="runner.description || runner.platform"
            />
          </el-select>
          <p class="personal-muted">
            Choose where this Agent's projects execute tools. Project
            directories are created automatically. Your primary Runner is selected by default.
            The first Agent with a Runner becomes your primary Agent.
          </p> </el-form-item
        ><el-form-item label="Instructions (optional)"
          ><el-input
            v-model="newProfile.instructions"
            type="textarea"
            :rows="4"
            placeholder="What should this Agent help you with?" /></el-form-item
      ></el-form>
      <template #footer
        ><el-button @click="createOpen = false">Cancel</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="!newProfile.name.trim() || !newProfile.runnerId"
          @click="createAgent"
          >Create Agent</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>

<script setup>
import {
  computed,
  onActivated,
  onDeactivated,
  onBeforeUnmount,
  ref,
  watch,
} from "vue";
import {
  ElMessage,
  ElMessageBox,
  ElSelect,
  ElOption,
  ElCheckbox,
} from "element-plus";
import QRCode from "qrcode";
import { personalChatMessages } from "../utils/personalMessages";
import ChatPanel from "./ChatPanel.vue";
import { validateImageFiles, readImageAttachment, toImageRequest } from "../utils/images.js";
import ChatActions from "./ChatActions.vue";
import RenameDialog from "./RenameDialog.vue";
import RuntimeContextPanel from "./RuntimeContextPanel.vue";
import { useAgentHarness } from "../composables/useAgentHarness";
import AgentLearningPanel from "./AgentLearningPanel.vue";
import AgentTasksPanel from "./AgentTasksPanel.vue";
import { request } from "../api/http";

const emit = defineEmits(["open-project"]);
const learningPanelRef = ref(null);
const configurationSkillsRef = ref(null);
async function beforeTabLeave(next, previous) {
  const panel = previous === "learning" ? learningPanelRef : configurationSkillsRef;
  return (await panel.value?.beforeLeave?.()) !== false;
}
async function beforeConfigTabLeave(next, previous) {
  return previous !== "skills" || (await configurationSkillsRef.value?.beforeLeave?.()) !== false;
}
const configTab = ref("general"),
  knowledgeTab = ref("memory"),
  agentTools = ref([]);
const renaming = ref(null),
  renameTitle = ref("");
function renameChat(conversation) {
  renaming.value = conversation;
  renameTitle.value = conversation.title;
}
async function saveChatTitle() {
  await action(async () => {
    const chat = await post(
      agentPath() + `/conversations/${renaming.value.sessionId}/rename`,
      { title: renameTitle.value },
    );
    conversations.value = conversations.value.map((c) =>
      c.sessionId === chat.sessionId ? chat : c,
    );
    renaming.value = null;
  });
}
async function removeChat(conversation) {
  try {
    await ElMessageBox.confirm(
      "Delete this chat and its queued messages? Scheduled follow-ups will be detached. Projects, memory and Skills are kept. Feishu starts a new chat on the next message.",
      "Delete chat",
      {
        type: "warning",
        confirmButtonText: "Delete",
        cancelButtonText: "Cancel",
      },
    );
  } catch {
    return;
  }
  await action(async () => {
    await post(agentPath() + `/conversations/${conversation.sessionId}/delete`);
    conversations.value = conversations.value.filter(
      (c) => c.sessionId !== conversation.sessionId,
    );
    if (sessionId.value === conversation.sessionId) {
      stopObserving();
      sessionId.value = conversations.value[0]?.sessionId || "";
      messages.value = [];
      draft.value = "";
      await loadConversation();
    }
    await refresh();
  });
}
function toggleTool(name, enabled) {
  const disabled = new Set(profile.value.disabledTools || []);
  if (enabled) disabled.delete(name);
  else disabled.add(name);
  profile.value.disabledTools = [...disabled];
}
function toolActionEnabled(name, action) {
  const disabled = profile.value?.disabledTools || [];
  return !disabled.includes(name) && !action.disabledKeys.some(key => disabled.includes(key));
}
function toggleToolAction(name, action, enabled) {
  const disabled = new Set(profile.value.disabledTools || []);
  if (enabled) action.disabledKeys.forEach(key => disabled.delete(key));
  else disabled.add(`${name}_${action.name}`);
  profile.value.disabledTools = [...disabled];
}
async function chooseAgent(id) {
  const agent = agents.value.find((a) => a.id === id);
  if (agent) await selectAgent(agent);
}
async function beforeLeave() {
  if ((await learningPanelRef.value?.beforeLeave?.()) === false) return false;
  if ((await configurationSkillsRef.value?.beforeLeave?.()) === false) return false;
  if (!profile.value || JSON.stringify(profile.value) === lastProfile)
    return true;
  try {
    await ElMessageBox.confirm(
      "Discard unsaved Agent configuration changes?",
      "Unsaved changes",
      { confirmButtonText: "Discard", cancelButtonText: "Keep editing" },
    );
    resetProfile();
    return true;
  } catch {
    return false;
  }
}
defineExpose({ beforeLeave });
const base = "/api/v1/personal";
const {
  messages: timeline,
  messageRevision: chatRevision,
  contextUsage: chatUsage,
  observeSession,
  stopObserving,
} = useAgentHarness({
  autoStart: false,
  onSessionMissing: (id) => {
    if (sessionId.value !== id) return;
    sessionId.value = "";
    conversations.value = conversations.value.filter((c) => c.sessionId !== id);
    messages.value = [];
    error.value =
      "This chat no longer exists. Choose another chat or start a new one.";
  },
});
const inboxRevision = ref(0);
const activeConversation = computed(
  () =>
    conversations.value.find((c) => c.sessionId === sessionId.value) || null,
);
const agentRunning = computed(() =>
  messages.value.some((m) => ["PENDING", "RUNNING"].includes(m.status)),
);
const chatStatus = computed(() =>
  !selected.value?.enabled
    ? "Paused — resume this Agent to send messages"
    : agentRunning.value
      ? "Working on your request…"
      : "Your personal Agent · projects, tools and memory",
);
const displayMessages = computed(() => activeConversation.value
  ? personalChatMessages(timeline.value, messages.value, agents.value)
  : []);
const defaults = ref({ primaryAgentId: null, primaryRunnerId: null });
const orderedAgents = computed(() => [...agents.value].sort((a, b) => Number(b.id === defaults.value.primaryAgentId) - Number(a.id === defaults.value.primaryAgentId)));
const agents = ref([]),
  selectedId = ref(""),
  conversations = ref([]),
  sessionId = ref("");
const defaultWorkspace = computed(() => {
  const root = runners.value.find(
    (r) => r.runnerId === profile.value?.runnerId,
  )?.workspaceRoot;
  return root && selectedId.value
    ? `${root.replace(/[\\/]$/, "")}/agents/${selectedId.value}/`
    : "";
});
const selected = computed(() =>
  agents.value.find((a) => a.id === selectedId.value),
);
const loading = ref(true),
  busy = ref(false),
  error = ref(""),
  tab = ref("chat");
const profile = ref(null),
  draft = ref(""),
  modelId = ref(""), draftImages = ref([]), addingImages = ref(false), imageError = ref(""),
  messages = ref([]),
  cards = ref([]),
  projectSessions = ref([]),
  runners = ref([]);
const projectGroups = computed(() => {
  const groups = new Map();
  for (const session of projectSessions.value) {
    if (!groups.has(session.projectId))
      groups.set(session.projectId, {
        id: session.projectId,
        name: session.projectName,
        workspace: session.workspacePath,
        sessions: [],
      });
    groups.get(session.projectId).sessions.push(session);
  }
  return [...groups.values()];
});
function openProject(id) {
  emit("open-project", id, selectedId.value);
}
const createOpen = ref(false),
  newProfile = ref({ name: "", description: "", instructions: "", runnerId: "" });
watch(createOpen, (open) => {
  if (open) newProfile.value = {
    name: "", description: "", instructions: "",
    runnerId: defaults.value.primaryRunnerId || runners.value.find((r) => r.online)?.runnerId || "",
  };
});
const feishu = ref({ available: false }),
  credentials = ref({ appId: "", appSecret: "", brand: "feishu" }),
  qrImage = ref("");
let timer,
  polling = false,
  active = false,
  lastProfile = "";
const clone = (value) => JSON.parse(JSON.stringify(value));
const post = (path, body = {}) =>
  request(base + path, { method: "POST", body: JSON.stringify(body) });
const agentPath = () => `/agents/${selectedId.value}`;
async function action(fn) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
    ElMessage.error(e.message);
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  if (polling || !active) return;
  polling = true;
  try {
    const [profiles, preferences] = await Promise.all([request(base + "/agents"), request(base + "/defaults")]);
    defaults.value = preferences;
    agents.value = profiles;
    if (selectedId.value && !profiles.some((p) => p.id === selectedId.value)) {
      stopObserving();
      selectedId.value = "";
      sessionId.value = "";
      conversations.value = [];
      messages.value = [];
      profile.value = null;
    }
    runners.value = await request("/api/v1/runners").catch(() => []);
    if (!selectedId.value && profiles.length) await selectAgent(profiles.find(p => p.id === preferences.primaryAgentId) || profiles[0]);
    else if (
      selected.value &&
      profile.value &&
      JSON.stringify(profile.value) === lastProfile
    )
      resetProfile();
    if (selectedId.value) {
      const id = selectedId.value;
      const [chats, tasks, projects] = await Promise.all([
        request(base + `/agents/${id}/conversations`),
        request(base + `/agents/${id}/cards`),
        request(base + `/agents/${id}/projects`),
      ]);
      if (id !== selectedId.value) return;
      conversations.value = chats;
      cards.value = tasks;
      projectSessions.value = projects;
      if (!chats.some((c) => c.sessionId === sessionId.value)) {
        stopObserving();
        sessionId.value = chats[0]?.sessionId || "";
        messages.value = [];
      }
      await loadConversation();
      await loadFeishu();
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
    polling = false;
  }
}
function resetProfile() {
  profile.value = clone(selected.value);
  lastProfile = JSON.stringify(profile.value);
}
async function openDelegation(agentId, conversationId) {
  const agent = agents.value.find(a => a.id === agentId);
  if (!agent) { ElMessage.error('Delegated Agent is unavailable'); return }
  await selectAgent(agent);
  if (selectedId.value === agentId) await openAgentConversation(conversationId);
}
async function selectAgent(agent, force = false) {
  if (busy.value && !force) return;
  if (!force && !(await beforeLeave())) return;
  stopObserving();
  selectedId.value = agent.id;
  sessionId.value = "";
  cards.value = [];
  projectSessions.value = [];
  messages.value = [];
  draft.value = ""; draftImages.value = []; modelId.value = ""; imageError.value = "";
  feishu.value = { available: false };
  resetProfile();
  const path = base + `/agents/${agent.id}`;
  const [chats, tasks, projects, catalog] = await Promise.all([
    request(path + "/conversations"),
    request(path + "/cards"),
    request(path + "/projects"),
    request(path + "/tools"),
  ]);
  if (selectedId.value !== agent.id) return;
  agentTools.value = catalog;
  conversations.value = chats;
  cards.value = tasks;
  projectSessions.value = projects;
  sessionId.value = chats[0]?.sessionId || "";
  await loadConversation();
  await loadFeishu();
}
async function loadConversation() {
  if (!sessionId.value) return;
  const id = selectedId.value,
    session = sessionId.value;
  const data = await request(base + `/agents/${id}/conversations/${session}`);
  if (id !== selectedId.value || session !== sessionId.value) return;
  messages.value = data.messages;
  inboxRevision.value++;
  if (active && activeConversation.value)
    observeSession(activeConversation.value);
}
async function makePrimary() {
  if (!(await beforeLeave())) return;
  await action(async () => {
    defaults.value = await post('/defaults', { primaryAgentId: selectedId.value, expectedPrimaryAgentId: defaults.value.primaryAgentId });
    ElMessage.success('Primary Agent updated');
  });
}
async function createAgent() {
  await action(async () => {
    const agent = await post("/agents", { ...newProfile.value, enabled: true });
    agents.value.push(agent);
    defaults.value = await request(base + "/defaults");
    createOpen.value = false;
    newProfile.value = { name: "", description: "", instructions: "", runnerId: "" };
    await selectAgent(agent, true);
    await createConversation("Chat 1");
  });
}
async function createConversation(title) {
  const c = await post(agentPath() + "/conversations", { title });
  conversations.value.push(c);
  sessionId.value = c.sessionId;
  await loadConversation();
  tab.value = "chat";
}
async function newChat() {
  await action(() =>
    createConversation(`Chat ${conversations.value.length + 1}`),
  );
}
async function addImages(files) {
  const result = validateImageFiles(files, draftImages.value); imageError.value = result.errors.join(' '); addingImages.value = true;
  const current = sessionId.value;
  try { const images = await Promise.all(result.accepted.map(readImageAttachment)); if (current === sessionId.value) draftImages.value.push(...images) } catch (e) { imageError.value = e.message } finally { addingImages.value = false }
}
async function send() {
  if ((!draft.value.trim() && !draftImages.value.length) || !selected.value?.enabled || busy.value || addingImages.value) return;
  await action(async () => {
    await post(agentPath() + `/conversations/${sessionId.value}`, {
      text: draft.value,
      modelId: modelId.value || null, images: toImageRequest(draftImages.value),
      messageId: crypto.randomUUID(),
    });
    draft.value = ""; draftImages.value = []; imageError.value = "";
    await loadConversation();
  });
}
function knowledgeSaved(saved) {
  agents.value = agents.value.map((a) => (a.id === saved.id ? saved : a));
  if (JSON.stringify(profile.value) === lastProfile) resetProfile();
}
async function openAgentConversation(id) {
  if (busy.value) return;
  if (sessionId.value !== id) {
    draft.value = ""; draftImages.value = []; modelId.value = ""; imageError.value = "";
    stopObserving();
  }
  sessionId.value = id;
  tab.value = "chat";
  await loadConversation();
}
async function saveProfile() {
  await action(async () => {
    const saved = await post("/agents", profile.value);
    agents.value = agents.value.map((a) => (a.id === saved.id ? saved : a));
    resetProfile();
    ElMessage.success("Saved");
  });
}
async function reloadProfile() {
  await refresh();
  resetProfile();
}
async function togglePause() {
  await action(async () => {
    const saved = await post("/agents", {
      ...selected.value,
      enabled: !selected.value.enabled,
    });
    agents.value = agents.value.map((a) => (a.id === saved.id ? saved : a));
    if (JSON.stringify(profile.value) === lastProfile) resetProfile();
  });
}
async function openTaskConversation(id) {
  if (!conversations.value.some(chat => chat.sessionId === id)) return;
  stopObserving();
  sessionId.value = id;
  messages.value = [];
  tab.value = "chat";
  await loadConversation();
}
async function loadFeishu() {
  const id = selectedId.value;
  try {
    const data = await request(base + `/agents/${id}/feishu`);
    if (id === selectedId.value) feishu.value = data;
  } catch {
    if (id === selectedId.value) feishu.value = { available: false };
  }
}
watch(
  () => feishu.value.authorizationUrl,
  async (url) => {
    const image = url
      ? await QRCode.toDataURL(url, { width: 220, margin: 2 })
      : "";
    if (url === feishu.value.authorizationUrl) qrImage.value = image;
  },
);
async function registerFeishu() {
  await action(async () => {
    feishu.value = await post(agentPath() + "/feishu/register");
  });
}
async function connectFeishu() {
  await action(async () => {
    feishu.value = await post(
      agentPath() + "/feishu/connect",
      credentials.value,
    );
    credentials.value.appSecret = "";
  });
}
async function reconnectFeishu() {
  await action(async () => {
    feishu.value = await post(agentPath() + "/feishu/reconnect");
  });
}
async function disconnectFeishu() {
  await action(async () => {
    await post(agentPath() + "/feishu/disconnect");
    await loadFeishu();
  });
}
async function retryDelivery(message) {
  await action(async () => {
    await post(agentPath() + "/feishu/retry", { messageId: message.id });
    await loadConversation();
  });
}
onActivated(() => {
  active = true;
  refresh();
  timer = setInterval(refresh, 1500);
});
onDeactivated(() => {
  stopObserving();
  active = false;
  clearInterval(timer);
});
onBeforeUnmount(() => {
  active = false;
  clearInterval(timer);
});
</script>

<style scoped>
.personal-agent-select {
  margin-top: 20px;
}
.personal-agent-select :deep(.el-select) {
  width: 100%;
}
.agent-option-state {
  margin-left: 16px;
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.personal-tool {
  border-bottom: 1px solid var(--el-border-color);
  padding: 16px 0;
}
.personal-tool-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0 16px;
  margin-bottom: 8px;
}
.personal-tool-actions .el-checkbox { margin-right: 0; }
.personal-tool p {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.personal-tool pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.personal-tool summary {
  cursor: pointer;
}

.personal-panel {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  width: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--el-bg-color, #fff);
}
.personal-directory {
  padding: 20px 16px;
  border-right: 1px solid var(--el-border-color-light);
  overflow: auto;
}
.personal-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.personal-heading h2 {
  margin: 0;
  font-size: 18px;
}
.personal-muted,
small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.personal-agent-choice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 14px 10px;
  margin-top: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.personal-agent-choice.selected {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}
.personal-agent-choice strong {
  overflow-wrap: anywhere;
}
.personal-detail {
  padding: 16px 24px;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.personal-tabs {
  flex: 1;
  min-height: 0;
}
.personal-tabs :deep(.el-tabs__content) {
  height: calc(100% - 56px);
  overflow: auto;
}
.personal-tabs :deep(#pane-board) {
  height: 100%;
}
.personal-tabs :deep(#pane-chat) {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.personal-conversation-bar {
  padding: 6px 0 16px;
}
.personal-chat-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  gap: 16px;
}
.personal-conversations {
  margin-top: 20px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.personal-conversation {
  text-align: left;
  padding: 10px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.personal-conversation.selected {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.personal-chat-layout :deep(.chat-panel) {
  flex: 1;
  min-width: 0;
  height: 100%;
}
.personal-chat-layout :deep(.panel-header) {
  padding: 10px 22px;
}
.personal-chat-layout :deep(.composer) {
  margin: 12px 22px;
  padding: 12px 16px;
}
.personal-chat-layout :deep(textarea.composer-input) {
  min-height: 48px;
}
@media (max-width: 760px) {
  .personal-chat-layout {
    flex-direction: column;
  }
  .personal-conversations {
    flex: 0 0 auto;
    flex-direction: row;
    max-height: 80px;
  }
  .personal-conversation {
    min-width: 100px;
  }
}
.personal-error {
  color: var(--el-color-danger);
  white-space: pre-wrap;
  font-size: 13px;
}
.personal-card {
  padding: 14px;
  margin-bottom: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.personal-card button {
  border: 0;
  background: none;
  color: inherit;
  text-align: left;
  padding: 0;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.personal-card p {
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
}
.personal-form-row {
  display: flex;
  gap: 24px;
}
.personal-checkbox {
  margin-right: 16px;
}
select,
input[type="datetime-local"] {
  padding: 9px;
  border: 1px solid var(--el-border-color);
  border-radius: 5px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  max-width: 100%;
  min-width: 180px;
}
.personal-qr {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  margin: 20px 0;
}
.personal-qr img {
  border-radius: 10px;
}
.personal-manual {
  margin: 24px 0;
}
.personal-manual > summary {
  cursor: pointer;
}
.personal-manual p {
  font-size: 13px;
  line-height: 1.6;
}
@media (max-width: 800px) {
  .personal-panel {
    grid-template-columns: 1fr;
    overflow: auto;
  }
  .personal-directory {
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-light);
    max-height: 220px;
  }
  .personal-detail {
    padding: 16px;
    min-height: 620px;
  }
  .personal-tabs {
    height: auto;
  }
  .personal-tabs :deep(#pane-board) {
    height: 720px;
  }
  .personal-tabs :deep(#pane-chat) {
    height: 650px;
  }
  .personal-heading {
    flex-wrap: wrap;
  }
}
</style>
