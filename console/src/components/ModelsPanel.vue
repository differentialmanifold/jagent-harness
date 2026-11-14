<template>
  <section class="models-settings">
    <header class="models-heading">
      <p>
        {{
          config.source === "environment"
            ? "Loaded from the server environment."
            : "Using settings saved on this server."
        }}
        You can edit these models or add another. Saved settings take priority
        over environment variables.
      </p>
      <el-button @click="add">Add model</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <article v-for="model in config.models" :key="model.id" class="model-card">
      <header class="model-heading">
        <h3>{{ model.model || "New model" }}</h3>
        <el-radio
          v-model="config.defaultModelId"
          :value="model.id"
          :disabled="!model.enabled"
          >Default</el-radio
        >
        <el-button
          v-if="config.models.length > 1"
          text
          :icon="Delete"
          aria-label="Remove model"
          title="Remove model"
          @click="remove(model)"
        />
      </header>
      <el-form label-position="top">
        <div class="model-fields">
          <el-form-item label="Model"
            ><el-input
              v-model="model.model"
              placeholder="JAGENT_MODEL"
              maxlength="256"
          /></el-form-item>
          <el-form-item label="Base URL"
            ><el-input
              v-model="model.baseUrl"
              placeholder="JAGENT_OPENAI_BASE_URL"
          /></el-form-item>
          <el-form-item label="API key"
            ><el-input
              v-model="model.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                model.credentialsConfigured
                  ? 'Configured · leave empty to keep'
                  : 'JAGENT_OPENAI_API_KEY (if required)'
              "
          /></el-form-item>
        </div>
        <div class="model-image-input">
          <label :for="`model-image-${model.id}`">
            <strong>Image input</strong>
            <span>Enable if this model can read image attachments.</span>
          </label>
          <el-switch
            :id="`model-image-${model.id}`"
            :aria-label="`Image input for ${model.model || 'new model'}`"
            :model-value="model.inputTypes.includes('image')"
            @update:model-value="setImageInput(model, $event)"
          />
        </div>
        <details class="model-options">
          <summary>More options</summary>
          <div class="model-options-grid">
            <el-form-item label="Provider"
              ><el-input
                v-model="model.provider"
                placeholder="openai-compatible"
            /></el-form-item>
            <el-form-item label="Temperature"
              ><el-input-number
                v-model="model.temperature"
                :precision="2"
                :step="0.1"
                :value-on-clear="null"
                placeholder="Provider default"
            /></el-form-item>
            <el-form-item label="Context window (tokens)"
              ><el-input-number
                v-model="model.contextWindowTokens"
                :min="1024"
                :max="10000000"
            /></el-form-item>
            <el-form-item label="Timeout (seconds)"
              ><el-input-number
                v-model="model.timeoutSeconds"
                :min="1"
                :max="3600"
            /></el-form-item>
          </div>
          <div class="model-flags">
            <el-checkbox v-model="model.enabled">Enabled</el-checkbox
            ><el-checkbox v-model="model.streamEnabled"
              >Stream responses</el-checkbox
            ><el-checkbox v-model="model.includeUsage"
              >Include token usage</el-checkbox
            >
          </div>
          <el-form-item label="Capabilities (optional)"
            ><el-input
              v-model="model.description"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="4000"
              placeholder="Describe strengths and limitations to help users choose a model."
          /></el-form-item>
        </details>
      </el-form>
      <footer>
        <el-button text :disabled="dirty || busy" @click="test(model)"
          >Test connection</el-button
        >
      </footer>
    </article>
    <div class="models-actions">
      <el-button type="primary" :loading="busy" :disabled="!dirty" @click="save"
        >Save</el-button
      ><el-button :disabled="busy || !dirty" @click="discard"
        >Discard changes</el-button
      ><span>Applies to new runs. API keys are stored on the server.</span>
    </div>
  </section>
</template>
<script setup>
import { computed, onMounted, ref } from "vue";
import {
  ElMessage,
  ElAlert,
  ElInputNumber,
  ElSwitch,
  ElRadio,
  ElCheckbox,
} from "element-plus";
import { Delete } from "@element-plus/icons-vue";
import { request } from "../api/http.js";
import {
  modelCatalog,
  loadModelCatalog,
} from "../composables/useModelCatalog.js";
const emit = defineEmits(["changed"]);
const config = ref({
    revision: 0,
    defaultModelId: "",
    models: [],
    source: "environment",
  }),
  saved = ref(""),
  error = ref(""),
  busy = ref(false);
const dirty = computed(
  () => !!saved.value && JSON.stringify(config.value) !== saved.value,
);
async function load() {
  try {
    config.value = JSON.parse(JSON.stringify(await loadModelCatalog()));
    saved.value = JSON.stringify(config.value);
    error.value = "";
  } catch (e) {
    error.value = e.message;
  }
}
function add() {
  config.value.models.push({
    id: `model_${crypto.randomUUID().replaceAll("-", "")}`,
    name: "",
    model: "",
    provider: "openai-compatible",
    baseUrl: "",
    apiKey: "",
    inputTypes: ["text"],
    capabilities: [],
    description: "",
    contextWindowTokens: 128000,
    temperature: null,
    timeoutSeconds: 120,
    streamEnabled: true,
    includeUsage: true,
    enabled: true,
  });
}
function remove(model) {
  config.value.models = config.value.models.filter((m) => m.id !== model.id);
  if (config.value.defaultModelId === model.id)
    config.value.defaultModelId = config.value.models[0]?.id || "";
}
function setImageInput(model, enabled) {
  model.inputTypes = [
    ...new Set([
      "text",
      ...model.inputTypes.filter((type) => type !== "image"),
      ...(enabled ? ["image"] : []),
    ]),
  ];
}
function discard() {
  if (saved.value) config.value = JSON.parse(saved.value);
  error.value = "";
}
async function save() {
  busy.value = true;
  try {
    const body = {
      ...config.value,
      models: config.value.models.map((m) => ({
        ...m,
        model: m.model.trim(),
        name: m.model.trim().slice(0, 128),
      })),
    };
    config.value = await request("/api/v1/models", {
      method: "POST",
      body: JSON.stringify(body),
    });
    modelCatalog.value = JSON.parse(JSON.stringify(config.value));
    saved.value = JSON.stringify(config.value);
    error.value = "";
    ElMessage.success("Model settings saved");
    emit("changed");
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function test(model) {
  busy.value = true;
  try {
    await request(`/api/v1/models/${encodeURIComponent(model.id)}/test`, {
      method: "POST",
    });
    error.value = "";
    ElMessage.success("Connection succeeded");
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
defineExpose({ hasUnsavedChanges: () => dirty.value, discardChanges: discard });
onMounted(load);
</script>
<style scoped>
.models-settings {
  padding: 24px;
  overflow: auto;
  height: 100%;
  box-sizing: border-box;
}
.models-heading,
.model-heading,
.models-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.models-heading p {
  flex: 1;
  min-width: 220px;
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.model-card {
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  padding: 20px;
  margin: 20px 0;
}
.model-heading {
  margin-bottom: 18px;
}
.model-heading h3 {
  flex: 1;
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 16px;
}
.model-heading :deep(.el-radio) {
  margin-right: 0;
}
.model-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.model-fields :deep(.el-form-item) {
  margin-bottom: 14px;
}
.model-image-input {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 4px 0 16px;
}
.model-image-input label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  cursor: pointer;
  font-size: 14px;
}
.model-image-input label span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.model-options summary {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  padding-block: 4px;
}
.model-options[open] summary {
  margin-bottom: 18px;
}
.model-options-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
.model-options-grid :deep(.el-input-number) {
  width: 100%;
}
.model-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin-bottom: 18px;
}
.model-card footer {
  display: flex;
  justify-content: flex-end;
}
.models-actions span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
@media (max-width: 900px) {
  .model-fields {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
}
@media (max-width: 600px) {
  .models-settings {
    padding: 16px;
  }
  .model-options-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
