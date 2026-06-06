<template>
  <details class="tool-run" :open="isFailedToolMessage(message)">
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
  toolResultItems,
  toolStatusLabel,
  toolStderr,
  toolStdout,
  toolTextContent
} from '../utils/toolDisplay'

defineProps({
  message: { type: Object, required: true }
})
</script>
