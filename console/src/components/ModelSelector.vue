<template>
  <el-dropdown
    trigger="click"
    placement="top-end"
    :disabled="disabled || !models.length"
    @visible-change="(open) => open && load()"
    @command="select"
  >
    <button
      class="model-trigger"
      type="button"
      :disabled="disabled"
      :title="`Model: ${currentName}`"
      aria-label="Choose model"
    >
      <span>{{ currentName }}</span
      ><el-icon><ArrowDown /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu class="model-menu">
        <el-dropdown-item
          v-for="model in models"
          :key="model.id"
          :command="model.id"
        >
          <span class="model-option-name">{{ model.model || model.name }}</span>
          <span v-if="model.id === defaultId" class="model-default-label"
            >Default</span
          >
          <el-icon v-if="model.id === selectedId" class="model-option-check"
            ><Check
          /></el-icon>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
<script setup>
import { computed, onMounted, onActivated } from "vue";
import { ArrowDown, Check } from "@element-plus/icons-vue";
import {
  modelCatalog,
  loadModelCatalog,
} from "../composables/useModelCatalog.js";
const props = defineProps({
  modelValue: { type: String, default: "" },
  disabled: Boolean,
});
const emit = defineEmits(["update:modelValue"]);
const models = computed(
    () => modelCatalog.value?.models.filter((m) => m.enabled) || [],
  ),
  defaultId = computed(() => modelCatalog.value?.defaultModelId || "");
const selectedId = computed(() => props.modelValue || defaultId.value);
const currentName = computed(() => {
  const model = models.value.find((m) => m.id === selectedId.value);
  return model?.model || model?.name || "Model";
});
function select(id) {
  emit("update:modelValue", id === defaultId.value ? "" : id);
}
async function load() {
  try {
    await loadModelCatalog();
  } catch {
    /* Keep the last known label while reconnecting. */
  }
}
onMounted(load);
onActivated(load);
</script>
<style scoped>
.model-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 220px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  padding: 7px 9px;
  color: var(--el-text-color-regular);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.model-trigger span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-trigger .el-icon {
  font-size: 11px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}
.model-trigger:hover:not(:disabled) {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}
.model-trigger:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}
.model-trigger:disabled {
  cursor: default;
  opacity: 0.6;
}
.model-option-name {
  flex: 1;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.model-default-label {
  margin-left: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.model-option-check {
  margin-left: 8px;
  color: var(--el-color-primary);
}
@media (max-width: 600px) {
  .model-trigger {
    max-width: 40vw;
    padding-inline: 5px;
  }
}
</style>
