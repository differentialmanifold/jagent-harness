<template>
  <article :class="messageClass(message)">
    <ToolMessage
      v-if="message.role === 'tool'"
      :message="message"
      @resolve-approval="$emit('resolveToolApproval', $event)"
    />

    <template v-else>
      <div class="message-meta">
        <el-tag size="small" :type="message.role === 'assistant' ? 'primary' : 'info'">{{ message.role }}</el-tag>
        <el-tag
          v-if="message.stopReason === 'aborted'"
          size="small"
          type="warning"
          effect="plain"
        >
          Stopped by user
        </el-tag>
      </div>
      <div v-if="message.thinking && !message.content" class="assistant-thinking" aria-live="polite">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>Thinking</span>
        <span class="activity-dots" aria-hidden="true"><i></i><i></i><i></i></span>
      </div>
      <pre v-else-if="message.content"><span>{{ message.content }}</span><span
        v-if="message.streaming"
        class="stream-cursor"
        aria-hidden="true"
      ></span></pre>

      <div v-if="message.toolCalls && message.toolCalls.length > 0" class="tool-call-list">
        <div v-for="call in message.toolCalls" :key="call.toolCallId || call.id || call.name" class="tool-call-card">
          <strong>{{ call.name }}</strong>
          <code>{{ summarizeToolArguments(call) }}</code>
        </div>
      </div>
    </template>
  </article>
</template>

<script setup>
import { Loading } from '@element-plus/icons-vue'
import ToolMessage from './ToolMessage.vue'
import { messageClass, summarizeToolArguments } from '../utils/toolDisplay'

defineProps({
  message: { type: Object, required: true }
})

defineEmits(['resolveToolApproval'])
</script>
