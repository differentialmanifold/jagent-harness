<template>
  <article :class="messageClass(message)">
    <ToolMessage v-if="message.role === 'tool'" :message="message" />

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
      <pre v-if="message.content">{{ message.content }}</pre>

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
import ToolMessage from './ToolMessage.vue'
import { messageClass, summarizeToolArguments } from '../utils/toolDisplay'

defineProps({
  message: { type: Object, required: true }
})
</script>
