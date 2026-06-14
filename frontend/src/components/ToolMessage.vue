<template>
  <div v-if="message.running" class="tool-run tool-run-active" aria-live="polite">
    <div class="tool-run-summary">
      <span class="tool-status">
        <el-icon class="is-loading"><Loading /></el-icon>
      </span>
      <span class="tool-summary">
        <strong>{{ toolMessageTitle(message) }}</strong>
        <small>{{ toolRunningSubtitle(message) }} · {{ elapsedText }}</small>
      </span>
    </div>
    <div class="tool-progress-track" aria-hidden="true"><span></span></div>
  </div>

  <details v-else class="tool-run" :open="isFailedToolMessage(message)">
    <summary>
      <span class="tool-status">{{ toolStatusLabel(message) }}</span>
      <span class="tool-summary">
        <strong>{{ toolMessageTitle(message) }}</strong>
        <small v-if="isEditDiffMessage(message)" class="edit-stats">
          <span class="edit-added">+{{ editAdditions(message) }}</span>
          <span class="edit-removed">-{{ editDeletions(message) }}</span>
        </small>
        <small v-else>{{ toolMessageSubtitle(message) }}</small>
      </span>
    </summary>

    <div class="tool-details">
      <div v-if="isEditDiffMessage(message)" class="edit-diff-card">
        <div class="edit-diff-title">Details</div>
        <div v-for="hunk in editDiffHunks(message)" :key="diffHunkKey(hunk)" class="diff-hunk">
          <div class="diff-hunk-header">
            @@ -{{ hunk.oldStart }},{{ hunk.oldLines }} +{{ hunk.newStart }},{{ hunk.newLines }} @@
          </div>
          <div
            v-for="(line, index) in hunk.lines"
            :key="`${diffHunkKey(hunk)}:${index}`"
            :class="['diff-line', diffLineClass(line)]"
          >
            <span class="diff-line-number">{{ formatDiffLineNumber(line.oldLine) }}</span>
            <span class="diff-line-number">{{ formatDiffLineNumber(line.newLine) }}</span>
            <span class="diff-line-prefix">{{ diffLinePrefix(line) }}</span>
            <code>{{ line.content || ' ' }}</code>
          </div>
        </div>
      </div>

      <div v-else-if="toolCommand(message)" class="tool-field">
        <span>Command</span>
        <pre>{{ toolCommand(message) }}</pre>
      </div>
      <div v-if="toolStdout(message)" class="tool-field">
        <span>stdout</span>
        <pre>{{ toolStdout(message) }}</pre>
      </div>
      <div v-if="toolStderr(message)" class="tool-field error">
        <span>stderr</span>
        <pre>{{ toolStderr(message) }}</pre>
      </div>
      <div v-if="toolTextContent(message)" class="tool-field">
        <span>content</span>
        <pre>{{ toolTextContent(message) }}</pre>
      </div>

      <ul v-if="toolResultItems(message).length > 0" class="tool-result-list">
        <li v-for="item in toolResultItems(message)" :key="item.key">
          <strong>{{ item.title }}</strong>
          <span>{{ item.detail }}</span>
        </li>
      </ul>

    </div>
  </details>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import {
  diffHunkKey,
  diffLineClass,
  diffLinePrefix,
  editAdditions,
  editDeletions,
  editDiffHunks,
  formatDiffLineNumber,
  isEditDiffMessage,
  isFailedToolMessage,
  toolCommand,
  toolMessageSubtitle,
  toolMessageTitle,
  toolRunningSubtitle,
  toolResultItems,
  toolStatusLabel,
  toolStderr,
  toolStdout,
  toolTextContent
} from '../utils/toolDisplay'

const props = defineProps({
  message: { type: Object, required: true }
})

const now = ref(Date.now())
let timer = null

const elapsedText = computed(() => {
  const startedAt = Number(props.message.startedAt) || now.value
  const elapsedSeconds = Math.max(0, Math.floor((now.value - startedAt) / 1000))
  return elapsedSeconds < 60
    ? `${elapsedSeconds}s`
    : `${Math.floor(elapsedSeconds / 60)}m ${elapsedSeconds % 60}s`
})

watch(
  () => props.message.running,
  (running) => {
    if (running && !timer) {
      now.value = Date.now()
      timer = window.setInterval(() => {
        now.value = Date.now()
      }, 1000)
    } else if (!running && timer) {
      window.clearInterval(timer)
      timer = null
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})
</script>
