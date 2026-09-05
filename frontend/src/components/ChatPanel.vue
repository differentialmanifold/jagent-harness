<template>
  <section class="chat-panel">
    <header class="panel-header">
      <div>
        <h2>{{ currentSession ? currentSession.title : 'No session selected' }}</h2>
        <p>{{ statusText }}</p>
      </div>
      <el-tag
        round
        :type="modelConfigured ? 'success' : 'warning'"
      >
        {{ modelConfigured ? 'Model configured' : 'Model configuration missing' }}
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
      <input
        ref="imageInputEl"
        class="visually-hidden"
        type="file"
        :accept="IMAGE_FILE_ACCEPT"
        multiple
        tabindex="-1"
        @change="handleImageSelection"
      />
      <div v-if="draftImages.length" class="composer-images" aria-label="Attached images">
        <div
          v-for="(image, index) in draftImages"
          :key="image.imageId || `${image.name}:${index}`"
          class="composer-image"
        >
          <img :src="image.url" :alt="image.name || `Attached image ${index + 1}`" />
          <button
            class="composer-image-remove"
            type="button"
            :aria-label="`Remove ${image.name || `image ${index + 1}`}`"
            :title="`Remove ${image.name || 'image'}`"
            @click="$emit('removeImage', image.imageId)"
          >
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>
      <el-input
        class="composer-input"
        type="textarea"
        resize="none"
        :model-value="draft"
        :disabled="!currentSession"
        :placeholder="composerPlaceholder"
        @update:model-value="$emit('update:draft', $event)"
        @keydown="handleKeydown"
        @paste="handlePaste"
      />
      <p v-if="imageError" class="composer-image-error" role="alert">{{ imageError }}</p>
      <div v-if="pendingInputs.length" class="pending-inputs" aria-live="polite">
        <div
          v-for="input in pendingInputs"
          :key="input.inputId"
          class="pending-input"
          :title="pendingInputTitle(input)"
        >
          <el-tag size="small" type="info" effect="plain">
            {{ input.status === 'submitting' ? 'Sending' : 'Queued' }}
          </el-tag>
          <span class="pending-input-content">{{ input.content || 'Image message' }}</span>
          <span v-if="pendingImageCount(input)" class="pending-input-image-count">
            {{ imageCountLabel(pendingImageCount(input)) }}
          </span>
        </div>
      </div>
      <div class="composer-footer">
        <div class="composer-left">
          <button
            class="composer-image-trigger"
            type="button"
            :disabled="!currentSession || addingImages || stopping"
            :aria-label="addingImages ? 'Adding images' : 'Add images'"
            :title="addingImages ? 'Adding images' : 'Add images'"
            @click="openImagePicker"
          >
            <el-icon :class="{ 'is-loading': addingImages }"><Picture /></el-icon>
          </button>
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
import { CaretBottom, Close, Lock, Picture, Top, Unlock } from '@element-plus/icons-vue'
import ContextUsageIndicator from './ContextUsageIndicator.vue'
import MessageItem from './MessageItem.vue'
import { IMAGE_FILE_ACCEPT, imageCountLabel } from '../utils/images'

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
  draft: { type: String, required: true },
  draftImages: { type: Array, default: () => [] },
  addingImages: { type: Boolean, default: false },
  imageError: { type: String, default: '' }
})

const emit = defineEmits([
  'update:draft',
  'update:approvalMode',
  'addImages',
  'removeImage',
  'send',
  'stop',
  'resolveToolApproval'
])
const AUTO_SCROLL_THRESHOLD_PX = 16
const messagesEl = ref(null)
const imageInputEl = ref(null)
const autoFollowMessages = ref(true)
let scrollFrame = null
let lastScrollTop = 0

const visibleMessages = computed(() => props.messages.filter((message) => {
  const isToolOnlyAssistant = message.role === 'assistant'
    && Array.isArray(message.toolCalls)
    && message.toolCalls.length > 0
    && !String(message.content || '').trim()
    && !String(message.reasoningContent || '').trim()
    && !(Array.isArray(message.images) && message.images.length > 0)
    && message.stopReason !== 'aborted'
  return !isToolOnlyAssistant
}))

const modelConfigured = computed(() => Boolean(
  String(props.provider?.model || '').trim()
  && String(props.provider?.baseUrl || '').trim()
))
const approvalModeLabel = computed(() => props.approvalMode === 'full_access' ? 'Full access' : 'Ask')
const composerPlaceholder = computed(() => {
  if (!props.running) return 'Ask the agent to inspect, edit, or plan work...'
  return 'Send another message...'
})
const hasMessage = computed(() => Boolean(props.draft.trim() || props.draftImages.length))
const showStopAction = computed(() => props.running && (props.stopping || !hasMessage.value))
const canSubmit = computed(() => Boolean(
  props.currentSession
  && hasMessage.value
  && !props.addingImages
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

function openImagePicker() {
  if (!props.currentSession || props.addingImages || props.stopping) return
  imageInputEl.value?.click()
}

function handleImageSelection(event) {
  const files = Array.from(event.target?.files || [])
  if (files.length > 0) {
    emit('addImages', files)
  }
  if (event.target) {
    event.target.value = ''
  }
}

function handlePaste(event) {
  const files = Array.from(event.clipboardData?.files || [])
    .filter((file) => String(file.type || '').startsWith('image/'))
  if (files.length === 0) return
  if (!props.currentSession || props.addingImages || props.stopping) return
  event.preventDefault()
  emit('addImages', files)
}

function pendingImageCount(input) {
  return Array.isArray(input?.images) ? input.images.length : 0
}

function pendingInputTitle(input) {
  const content = String(input?.content || '').trim()
  return content || imageCountLabel(pendingImageCount(input))
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
