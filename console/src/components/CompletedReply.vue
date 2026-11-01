<template>
  <div class="completed-reply">
    <details class="reply-process">
      <summary>{{ duration ? `Worked for ${duration}` : 'View work' }}</summary>
      <div class="reply-process-messages">
        <MessageItem v-for="message in entry.process" :key="message.messageId" :message="message" />
      </div>
    </details>
    <MessageItem v-if="entry.message.content || entry.message.images?.length" :message="entry.message" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import MessageItem from './MessageItem.vue'
import { formatDuration } from '../utils/format'

const props = defineProps({ entry: { type: Object, required: true } })
const duration = computed(() => formatDuration(props.entry.duration))
</script>

<style scoped>
.completed-reply { max-width: 900px; }
.reply-process { margin-bottom: 12px; border-bottom: 1px solid #d7dde6; }
.reply-process summary {
  padding: 12px 4px;
  color: #64748b;
  cursor: pointer;
  font-size: 13px;
}
.reply-process summary:hover { color: #334155; }
.reply-process summary:focus-visible { outline: 2px solid #409eff; outline-offset: 2px; }
.reply-process-messages { display: flex; flex-direction: column; gap: 12px; padding-bottom: 12px; }
</style>
