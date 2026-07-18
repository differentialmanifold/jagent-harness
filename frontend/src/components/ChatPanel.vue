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

    <div class="messages" ref="messagesEl" @scroll.passive="handleMessagesScroll">
      <MessageItem
        v-for="message in visibleMessages"
        :key="message.role === 'assistant' && message.turnId ? `assistant:${message.turnId}` : message.messageId"
        :message="message"
        @resolve-tool-approval="$emit('resolveToolApproval', $event)"
      />
    </div>

    <form class="composer" @submit.prevent="submit">
      <el-input
        class="composer-input"
        type="textarea"
        resize="none"
        :model-value="draft"
        :disabled="!currentSession"
        :placeholder="composerPlaceholder"
        @update:model-value="$emit('update:draft', $event)"
        @keydown="handleKeydown"
      />
      <div v-if="pendingInputs.length" class="pending-inputs" aria-live="polite">
        <div
          v-for="input in pendingInputs"
          :key="input.inputId"
          class="pending-input"
          :title="input.content"
        >
          <el-tag size="small" type="info" effect="plain">
            {{ input.status === 'submitting' ? 'Sending' : 'Queued' }}
          </el-tag>
          <span class="pending-input-content">{{ input.content || 'Pending input' }}</span>
        </div>
      </div>
      <div class="composer-footer">
        <div class="composer-left">
          <el-dropdown
            v-if="!running"
            class="approval-dropdown"
            popper-class="approval-dropdown-menu"
            trigger="click"
            :disabled="running"
            @command="selectApprovalMode"
          >
            <button class="approval-trigger" type="button" :disabled="running">
              <el-icon>
                <Lock v-if="approvalMode === 'ask_approval'" />
                <Unlock v-else />
              </el-icon>
              <span>{{ approvalModeLabel }}</span>
              <el-icon class="approval-caret"><CaretBottom /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ask_approval">
                  <el-icon><Lock /></el-icon>
                  <span>Ask</span>
                </el-dropdown-item>
                <el-dropdown-item command="full_access">
                  <el-icon><Unlock /></el-icon>
                  <span>Full access</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="composer-right">
          <ContextUsageIndicator :usage="contextUsage" />
          <el-button
            v-if="showStopAction"
            class="composer-stop"
            :disabled="stopping || !stopReady"
            native-type="button"
            :aria-label="stopping ? 'Stopping agent' : 'Stop agent'"
            :title="stopping ? 'Stopping agent' : 'Stop agent'"
            circle
            @click="$emit('stop')"
          >
            <span class="stop-glyph" aria-hidden="true"></span>
          </el-button>
          <el-button
            v-else
            class="composer-submit"
            type="primary"
            :icon="Top"
            :loading="submittingInput"
            :disabled="!canSubmit"
            native-type="submit"
            aria-label="Send message"
            title="Send message"
            circle
          />
        </div>
      </div>
    </form>
  </section>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { CaretBottom, Lock, Top, Unlock } from '@element-plus/icons-vue'
import ContextUsageIndicator from './ContextUsageIndicator.vue'
import MessageItem from './MessageItem.vue'

const props = defineProps({
  currentSession: { type: Object, default: null },
  messages: { type: Array, required: true },
  provider: { type: Object, default: null },
  statusText: { type: String, required: true },
  running: { type: Boolean, required: true },
  stopping: { type: Boolean, required: true },
  stopReady: { type: Boolean, required: true },
  activeRunId: { type: String, default: '' },
  pendingInputs: { type: Array, default: () => [] },
  submittingInput: { type: Boolean, default: false },
  messageRevision: { type: Number, default: 0 },
  approvalMode: { type: String, required: true },
  contextUsage: { type: Object, default: null },
  draft: { type: String, required: true }
})

const emit = defineEmits([
  'update:draft',
  'update:approvalMode',
  'send',
  'stop',
  'resolveToolApproval'
])
const AUTO_SCROLL_THRESHOLD_PX = 16
const messagesEl = ref(null)
const autoFollowMessages = ref(true)
let scrollFrame = null
let lastScrollTop = 0

const visibleMessages = computed(() => props.messages.filter((message) => {
  const isToolOnlyAssistant = message.role === 'assistant'
    && Array.isArray(message.toolCalls)
    && message.toolCalls.length > 0
    && !String(message.content || '').trim()
    && !String(message.reasoningContent || '').trim()
    && message.stopReason !== 'aborted'
  return !isToolOnlyAssistant
}))

const approvalModeLabel = computed(() => props.approvalMode === 'full_access' ? 'Full access' : 'Ask')
const composerPlaceholder = computed(() => {
  if (!props.running) return 'Ask the agent to inspect, edit, or plan work...'
  return 'Send another message...'
})
const hasDraft = computed(() => Boolean(props.draft.trim()))
const showStopAction = computed(() => props.running && (props.stopping || !hasDraft.value))
const canSubmit = computed(() => Boolean(
  props.currentSession
  && props.draft.trim()
  && !props.submittingInput
  && !props.stopping
  && (!props.running || (props.stopReady && props.activeRunId))
))

watch(
  () => props.messageRevision,
  () => scrollMessagesToBottom(),
)

watch(
  () => props.currentSession && props.currentSession.sessionId,
  () => {
    autoFollowMessages.value = true
    scrollMessagesToBottom({ force: true })
  }
)

function submit() {
  if (!canSubmit.value) return
  autoFollowMessages.value = true
  scrollMessagesToBottom({ force: true })
  emit('send')
}

function selectApprovalMode(mode) {
  if (props.running) return
  emit('update:approvalMode', mode === 'full_access' ? 'full_access' : 'ask_approval')
}

function handleKeydown(event) {
  if (event.key !== 'Enter') return
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}

function handleMessagesScroll() {
  const el = messagesEl.value
  if (!el) return
  const scrollingUp = el.scrollTop < lastScrollTop - 2
  lastScrollTop = el.scrollTop

  if (scrollingUp && !isAtBottom(el)) {
    autoFollowMessages.value = false
    return
  }
  if (isAtBottom(el)) {
    autoFollowMessages.value = true
  }
}

function scrollMessagesToBottom(options = {}) {
  if (!options.force && !autoFollowMessages.value) return
  if (scrollFrame) return
  scrollFrame = window.requestAnimationFrame(async () => {
    scrollFrame = null
    await nextTick()
    const el = messagesEl.value
    if (!el) return
    if (!options.force && !autoFollowMessages.value) return
    el.scrollTop = el.scrollHeight
    lastScrollTop = el.scrollTop
    autoFollowMessages.value = true
  })
}

function isAtBottom(el) {
  return distanceToBottom(el) <= AUTO_SCROLL_THRESHOLD_PX
}

function distanceToBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight
}
</script>
