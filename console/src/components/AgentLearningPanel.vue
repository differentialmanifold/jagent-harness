<template>
  <section class="learning-panel">
    <p v-if="error" role="alert" class="learning-error">{{ error }}</p>
    <div class="learning-heading">
      <p v-if="skillsOnly">
        Available methods, loaded by the Agent when needed. Built-in Skills are
        read-only; shared Skills are managed in System Settings → Agent defaults
        → Skills.
      </p>
      <p v-else>
        Keep useful methods, find past evidence, and review what your Agent
        learned.
      </p>
      <el-button :loading="busy" @click="refresh">Refresh</el-button>
    </div>
    <el-tabs v-model="view" :class="{ 'skills-only': skillsOnly }">
      <el-tab-pane label="Skills" name="skills">
        <div class="learning-heading">
          <p>New and learned Skills are private to this Agent.</p>
          <el-button @click="newSkill">New skill</el-button>
        </div>
        <div class="learning-files">
          <button
            v-for="file in skills"
            :key="file.path"
            :class="{ selected: editor?.path === file.path }"
            :aria-pressed="editor?.path === file.path"
            @click="openSkill(file)"
          >
            {{ file.path }}
            <small>{{ skillSource(file) }}</small>
          </button>
        </div>
        <el-form v-if="editor" label-position="top" @submit.prevent="saveSkill">
          <el-form-item label="Path">
            <el-input
              v-model="editor.path"
              :disabled="!editor.isNew"
              placeholder="skills/my-method/SKILL.md"
            />
          </el-form-item>
          <template v-if="editor.readOnly">
            <div class="learning-heading">
              <span class="learning-muted">{{ skillSource(editor) }}</span>
              <el-radio-group v-model="readTab" size="small">
                <el-radio-button value="preview">Preview</el-radio-button>
                <el-radio-button value="code">Code</el-radio-button>
              </el-radio-group>
            </div>
            <MarkdownContent
              v-if="readTab === 'preview'"
              class="skill-preview"
              :content="editor.path.endsWith('/SKILL.md') ? skillBody(editor.content) : editor.content"
            />
            <pre v-else>{{ editor.content }}</pre>
          </template>
          <el-form-item v-else label="Content">
            <el-input
              v-model="editor.content"
              type="textarea"
              :autosize="{ minRows: 10, maxRows: 25 }"
              :readonly="editor.readOnly"
            />
          </el-form-item>
          <el-form-item v-if="!editor.readOnly" label="Reason for this change">
            <el-input v-model="editor.reason" maxlength="512" />
          </el-form-item>
          <p v-if="!editor.readOnly">
            {{ editor.content.length.toLocaleString() }} / 40,000 characters
          </p>
          <el-button
            v-if="!editor.readOnly"
            type="primary"
            :loading="busy"
            @click="saveSkill"
            >Save skill</el-button
          >
          <el-button
            v-if="
              !editor.readOnly &&
              !editor.isNew &&
              editor.path.endsWith('/SKILL.md')
            "
            :loading="busy"
            @click="removeSkill"
            >Remove</el-button
          >
          <el-button @click="closeEditor">Close</el-button>
        </el-form>
      </el-tab-pane>
      <el-tab-pane v-if="!skillsOnly" label="Learning history" name="changes">
        <div class="learning-heading">
          <p>
            Every edit keeps its previous content and source. Undo is available
            for the latest edit to each file.
          </p>
          <el-button
            :disabled="!conversationId || !enabled"
            :loading="busy"
            @click="review"
            >Review this conversation</el-button
          >
        </div>
        <article
          v-for="change in changes"
          :key="change.id"
          class="learning-change"
        >
          <div class="learning-heading">
            <strong>{{
              change.path === "memory" ? "Memory" : change.path
            }}</strong>
            <small
              >{{ new Date(change.createdAt).toLocaleString() }} ·
              {{ change.origin.toLowerCase() }}</small
            >
          </div>
          <p>{{ change.reason }}</p>
          <el-button size="small" @click="inspectChange(change)"
            >View change</el-button
          >
          <el-button
            v-if="change.sessionId"
            size="small"
            @click="source(change)"
            >View evidence</el-button
          >
          <el-button
            v-if="change.canUndo"
            size="small"
            :loading="busy"
            @click="undo(change)"
            >Undo</el-button
          >
        </article>
        <el-empty
          v-if="!changes.length"
          description="Learned memories and skills will appear here."
          :image-size="60"
        />
        <el-button v-if="hasMore" :loading="busy" @click="moreChanges"
          >Load older changes</el-button
        >
        <details class="learning-reviews">
          <summary>Recent reviews</summary>
          <p v-for="item in reviews" :key="item.id">
            {{ new Date(item.createdAt).toLocaleString() }} ·
            {{ item.status.toLowerCase() }} ·
            {{ item.answer || item.error || "Queued for an idle moment" }}
          </p>
        </details>
      </el-tab-pane>
      <el-tab-pane v-if="!skillsOnly" label="Search history" name="search">
        <form class="learning-heading" @submit.prevent="search">
          <el-input
            v-model="query"
            placeholder="A phrase, error, or Chinese text…"
            maxlength="256"
            aria-label="Search history"
          />
          <select v-model="scope" aria-label="Search scope">
            <option value="all">My conversations & projects</option>
            <option value="personal">Agent conversations</option>
            <option value="projects">Projects</option>
          </select>
          <el-button
            native-type="submit"
            :loading="busy"
            :disabled="!query.trim()"
            >Search</el-button
          >
        </form>
        <p class="learning-muted">
          Literal phrase matching. Only this Agent's conversations and projects
          are searched.
        </p>
        <article
          v-for="hit in hits"
          :key="hit.messageId"
          class="learning-change"
        >
          <strong>{{ hit.title }} · {{ hit.role }}</strong>
          <pre>{{ hit.content }}</pre>
          <small v-if="hit.truncated">Excerpt shortened</small>
          <el-button size="small" @click="source(hit)"
            >Read surrounding messages</el-button
          >
        </article>
        <el-empty
          v-if="searched && !hits.length"
          description="No matches. Try a shorter phrase."
          :image-size="60"
        />
      </el-tab-pane>
    </el-tabs>
    <el-dialog
      v-model="changeOpen"
      title="Knowledge change"
      width="min(1100px, 96vw)"
    >
      <template v-if="detail">
        <p>{{ detail.reason }}</p>
        <div class="learning-diff">
          <section>
            <strong>Before</strong>
            <pre>{{ detail.beforeContent ?? "(File did not exist)" }}</pre>
          </section>
          <section>
            <strong>After</strong>
            <pre>{{ detail.afterContent ?? "(Removed)" }}</pre>
          </section>
        </div>
      </template>
    </el-dialog>
    <el-dialog
      v-model="evidenceOpen"
      title="Source evidence"
      width="min(900px, 96vw)"
    >
      <article
        v-for="message in evidence"
        :key="message.messageId"
        class="learning-change"
      >
        <strong>{{ message.role }}</strong>
        <pre>{{ message.content }}</pre>
        <small v-if="message.truncated">Message shortened</small>
      </article>
      <el-button v-if="evidence.length" @click="openSource"
        >Open conversation</el-button
      >
    </el-dialog>
  </section>
</template>

<script setup>
import { ref, watch, onBeforeUnmount, onMounted } from "vue";
import { ElMessageBox, ElMessage, ElRadioGroup, ElRadioButton } from "element-plus";
import { request } from "../api/http";
import MarkdownContent from "./MarkdownContent.vue";
import { skillBody } from "../utils/markdown";
const props = defineProps({
  agentId: String,
  conversationId: String,
  enabled: Boolean,
  revision: Number,
  skillsOnly: Boolean,
});
const readTab = ref("preview");
const skillSource = (file) =>
  file.source === "builtin"
    ? "Built-in · read-only"
    : file.source === "agent-global"
      ? "Agent defaults · read-only here"
      : "Private to this Agent";
const emit = defineEmits(["saved", "open-project", "open-conversation"]);
const view = ref("skills"),
  skills = ref([]),
  changes = ref([]),
  reviews = ref([]);
let savedEditor = "";
function editorValue() {
  return editor.value
    ? JSON.stringify([
        editor.value.path,
        editor.value.content,
        editor.value.reason,
      ])
    : "";
}
async function beforeLeave() {
  if (!editor.value || editor.value.readOnly || editorValue() === savedEditor)
    return true;
  try {
    await ElMessageBox.confirm(
      "Discard unsaved Skill changes?",
      "Unsaved changes",
      { confirmButtonText: "Discard", cancelButtonText: "Keep editing" },
    );
    editor.value = null;
    return true;
  } catch {
    return false;
  }
}
async function closeEditor() {
  if (await beforeLeave()) editor.value = null;
}
defineExpose({ beforeLeave });
const editor = ref(null),
  busy = ref(false),
  error = ref(""),
  hasMore = ref(false);
const query = ref(""),
  scope = ref("all"),
  hits = ref([]),
  searched = ref(false);
const changeOpen = ref(false),
  detail = ref(null),
  evidenceOpen = ref(false),
  evidence = ref([]);
const path = () =>
  `/api/v1/personal/agents/${encodeURIComponent(props.agentId)}`;
const post = (url, body) =>
  request(url, { method: "POST", body: JSON.stringify(body) });
let alive = true,
  timer;
onMounted(() => {
  if (props.skillsOnly) return;
  timer = setInterval(async () => {
    if (busy.value || !props.agentId) return;
    const id = props.agentId;
    try {
      const data = await request(path() + "/reviews");
      if (alive && id === props.agentId) reviews.value = data;
    } catch (e) {
      if (alive) error.value = e.message;
    }
  }, 3000);
});
onBeforeUnmount(() => {
  alive = false;
  clearInterval(timer);
});
async function act(fn) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function load() {
  const id = props.agentId;
  const [s, c, r] = await Promise.all([
    request(path() + "/skills"),
    props.skillsOnly ? [] : request(path() + "/learning"),
    props.skillsOnly ? [] : request(path() + "/reviews"),
  ]);
  if (!alive || id !== props.agentId) return;
  skills.value = s;
  changes.value = c;
  reviews.value = r;
  hasMore.value = c.length === 30;
}
const refresh = () => act(load);
watch(
  () => props.agentId,
  () => {
    editor.value = null;
    detail.value = null;
    hits.value = [];
    searched.value = false;
    changeOpen.value = false;
    evidenceOpen.value = false;
    void refresh();
  },
  { immediate: true },
);
watch(
  () => props.revision,
  () => {
    if (!busy.value) void refresh();
  },
);
async function newSkill() {
  if (!(await beforeLeave())) return;
  editor.value = {
    path: "skills/my-method/SKILL.md",
    content:
      "---\nname: my-method\ndescription: When to use this method\n---\n\n## Procedure\n\n## Pitfalls\n\n## Verification\n",
    revision: props.revision,
    reason: "",
    isNew: true,
    readOnly: false,
  };
  savedEditor = editorValue();
}
async function openSkill(file) {
  if (!(await beforeLeave())) return;
  await act(async () => {
    const data = await request(
      path() + "/skill?path=" + encodeURIComponent(file.path),
    );
    editor.value = {
      ...data,
      content: data.content || "",
      reason: "",
      isNew: false,
    };
    readTab.value = "preview";
    savedEditor = editorValue();
  });
}
async function saveSkill() {
  if (!editor.value || editor.value.readOnly) return;
  await act(async () => {
    const data = await post(path() + "/knowledge", {
      ...editor.value,
      action: "skills.save",
    });
    editor.value.revision = data.revision;
    editor.value.isNew = false;
    savedEditor = editorValue();
    emit("saved", data);
    await load();
    ElMessage.success("Skill saved");
  });
}
async function removeSkill() {
  if (!editor.value || editor.value.readOnly) return;
  await act(async () => {
    const data = await post(path() + "/knowledge", {
      ...editor.value,
      action: "skills.remove",
    });
    editor.value = null;
    emit("saved", data);
    await load();
  });
}
async function inspectChange(change) {
  await act(async () => {
    detail.value = await request(path() + "/learning/" + change.id);
    changeOpen.value = true;
  });
}
async function undo(change) {
  try {
    await ElMessageBox.confirm(
      "Restore the content before this edit? The undo is also recorded.",
      "Undo knowledge change",
    );
  } catch {
    return;
  }
  await act(async () => {
    const data = await post(path() + "/learning/" + change.id + "/undo", {
      revision: props.revision,
    });
    emit("saved", data);
    await load();
    ElMessage.success("Change undone");
  });
}
async function moreChanges() {
  await act(async () => {
    const rows = await request(
      path() + "/learning?beforeRevision=" + changes.value.at(-1).revision,
    );
    changes.value.push(...rows);
    hasMore.value = rows.length === 30;
  });
}
async function review() {
  await act(async () => {
    await post(path() + "/reviews", { sessionId: props.conversationId });
    await load();
    ElMessage.success("Review queued");
  });
}
async function search() {
  await act(async () => {
    hits.value = await request(
      path() +
        "/history/search?" +
        new URLSearchParams({ query: query.value, scope: scope.value }),
    );
    searched.value = true;
  });
}
async function source(item) {
  if (!item.messageId) {
    emit("open-conversation", item.sessionId);
    return;
  }
  await act(async () => {
    evidence.value = await request(
      path() +
        "/history/around?" +
        new URLSearchParams({
          sessionId: item.sessionId,
          messageId: item.messageId,
        }),
    );
    evidenceOpen.value = true;
  });
}
function openSource() {
  const first = evidence.value[0];
  evidenceOpen.value = false;
  emit(first.projectId ? "open-project" : "open-conversation", first.sessionId);
}
</script>

<style scoped>
.learning-panel {
  min-width: 0;
}
.skills-only :deep(.el-tabs__header) {
  display: none;
}
.skill-preview {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 16px;
  max-height: 60vh;
  overflow: auto;
}
.learning-files button.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.learning-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.learning-heading p,
.learning-muted,
small {
  color: var(--el-text-color-secondary);
}
.learning-files {
  display: grid;
  gap: 8px;
  margin-bottom: 20px;
}
.learning-files button {
  text-align: left;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: inherit;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.learning-files small {
  margin-left: 8px;
}
.learning-change {
  padding: 14px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
.learning-error {
  color: var(--el-color-danger);
}
.learning-reviews {
  margin-top: 20px;
}
.learning-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.learning-diff section {
  min-width: 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 55vh;
  overflow: auto;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}
select {
  padding: 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: inherit;
}
@media (max-width: 700px) {
  .learning-heading {
    flex-wrap: wrap;
  }
  .learning-diff {
    grid-template-columns: 1fr;
  }
}
</style>
