<template>
  <section class="chat-panel">
    <header class="panel-header">
      <div>
        <h2>{{ currentSession ? currentSession.title : 'No session selected' }}</h2>
        <p>{{ statusText }}</p>
      </div>
      <el-tag
        round
        :type="provider && provider.apiKeyConfigured ? 'success' : 'warning'"
      >
        {{ provider && provider.apiKeyConfigured ? 'API key ready' : 'API key missing' }}
      </el-tag>
    </header>

    <div class="messages" ref="messagesEl">
      <MessageItem
        v-for="message in visibleMessages"
        :key="message.messageId"
        :message="message"
        @resolve-tool-approval="$emit('resolveToolApproval', $event)"
      />
    </div>

    <form class="composer" @submit.prevent="submit">
      <div class="composer-toolbar">
        <el-radio-group
          class="approval-mode"
          :model-value="approvalMode"
          size="small"
          :disabled="running"
          aria-label="Tool access"
          @update:model-value="$emit('update:approvalMode', $event)"
        >
          <el-radio-button value="ask_approval">
            <el-icon><Lock /></el-icon>
            <span>Ask</span>
          </el-radio-button>
          <el-radio-button value="full_access">
            <el-icon><Unlock /></el-icon>
            <span>Full access</span>
          </el-radio-button>
        </el-radio-group>
      </div>
      <el-input
        class="composer-input"
        type="textarea"
        resize="none"
        :model-value="draft"
        :disabled="!currentSession || running"
        placeholder="Ask the agent to inspect, edit, or plan work..."
        @update:model-value="$emit('update:draft', $event)"
        @keydown="handleKeydown"
      />
      <el-button
        v-if="running"
        type="danger"
        :icon="VideoPause"
        :disabled="stopping || !stopReady"
        native-type="button"
        @click="$emit('stop')"
      >
        {{ stopping ? 'Stopping' : 'Stop' }}
      </el-button>
      <el-button
        v-else
        type="primary"
        :icon="Promotion"
        :disabled="!currentSession || !draft.trim()"
        native-type="submit"
      >
        Send
      </el-button>
    </form>
  </section>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElRadioButton, ElRadioGroup } from 'element-plus'
import { Lock, Promotion, Unlock, VideoPause } from '@element-plus/icons-vue'
import MessageItem from './MessageItem.vue'

const props = defineProps({
  currentSession: { type: Object, default: null },
  messages: { type: Array, required: true },
  provider: { type: Object, default: null },
  statusText: { type: String, required: true },
  running: { type: Boolean, required: true },
  stopping: { type: Boolean, required: true },
  stopReady: { type: Boolean, required: true },
  approvalMode: { type: String, required: true },
  draft: { type: String, required: true }
})

const emit = defineEmits(['update:draft', 'update:approvalMode', 'send', 'stop', 'resolveToolApproval'])
const messagesEl = ref(null)
let scrollFrame = null

const visibleMessages = computed(() => props.messages.filter((message) => {
  const isToolOnlyAssistant = message.role === 'assistant'
    && Array.isArray(message.toolCalls)
    && message.toolCalls.length > 0
    && !String(message.content || '').trim()
    && !String(message.reasoningContent || '').trim()
    && message.stopReason !== 'aborted'
  return !isToolOnlyAssistant
}))

watch(
  () => props.messages,
  () => scrollMessagesToBottom(),
  { deep: true }
)

function submit() {
  if (!props.currentSession || props.running || !props.draft.trim()) return
  emit('send')
}

function handleKeydown(event) {
  if (event.key !== 'Enter') return
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}

function scrollMessagesToBottom() {
  if (scrollFrame) return
  scrollFrame = window.requestAnimationFrame(async () => {
    scrollFrame = null
    await nextTick()
    const el = messagesEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}
</script>
