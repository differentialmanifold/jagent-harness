<template>
  <section class="chat-panel">
    <header class="panel-header">
      <div>
        <h2>{{ currentSession ? currentSession.title : 'No session selected' }}</h2>
        <p>{{ statusText }}</p>
      </div>
      <el-tag
        v-if="showModelStatus"
        round
        :type="modelConfigured ? 'success' : 'warning'"
      >
        {{ modelConfigured ? 'Model configured' : 'Model configuration missing' }}
      </el-tag>
    </header>

    <div class="messages" ref="messagesEl" @scroll.passive="handleMessagesScroll">
      <template v-for="entry in chatEntries" :key="entry.key">
        <CompletedReply v-if="entry.process" :entry="entry" />
        <template v-else>
          <MessageItem :message="entry.message" />
          <div v-if="entry.message.stopReason === 'failed'" class="failed-turn-actions">
            <span>The failed response is excluded from future context. You can retry or send another message.</span>
            <el-button v-if="retryEnabled" size="small" :disabled="running || disabled" @click="$emit('retry', entry.message)">Retry</el-button>
          </div>
        </template>
      </template>
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
      <ComposerInput
        :key="currentSession?.sessionId || 'no-session'"
        :model-value="draft"
        :rows="composerRows"
        :disabled="!currentSession || disabled"
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
            v-if="allowImages"
            class="composer-image-trigger"
            type="button"
            :disabled="!currentSession || addingImages || stopping"
            :aria-label="addingImages ? 'Adding images' : 'Add images'"
            :title="addingImages ? 'Adding images' : 'Add images'"
            @click="openImagePicker"
          >
            <el-icon :class="{ 'is-loading': addingImages }"><Picture /></el-icon>
          </button>
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
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Close, Picture, Top } from '@element-plus/icons-vue'
import ContextUsageIndicator from './ContextUsageIndicator.vue'
import MessageItem from './MessageItem.vue'
import CompletedReply from './CompletedReply.vue'
import { groupCompletedReplies } from '../utils/chatTimeline'
import ComposerInput from './ComposerInput.vue'
import { IMAGE_FILE_ACCEPT, imageCountLabel } from '../utils/images'

const props = defineProps({
  retryEnabled: { type: Boolean, default: false },
  active: { type: Boolean, default: true },
  composerRows: { type: Number, default: 3 },
  showModelStatus: { type: Boolean, default: true },
  allowImages: { type: Boolean, default: true },
  allowStop: { type: Boolean, default: true },
  allowQueue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '' },
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
  contextUsage: { type: Object, default: null },
  draft: { type: String, required: true },
  draftImages: { type: Array, default: () => [] },
  addingImages: { type: Boolean, default: false },
  imageError: { type: String, default: '' }
})

const emit = defineEmits([
  'update:draft',
  'addImages',
  'removeImage',
  'send',
  'retry',
  'stop'
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

const chatEntries = computed(() => groupCompletedReplies(visibleMessages.value))

const modelConfigured = computed(() => Boolean(
  String(props.provider?.model || '').trim()
  && String(props.provider?.baseUrl || '').trim()
))
const composerPlaceholder = computed(() => {
  if (props.placeholder) return props.placeholder
  if (!props.running) return 'Ask the agent to inspect, edit, or plan work...'
  return 'Send another message...'
})
const hasMessage = computed(() => Boolean(props.draft.trim() || props.draftImages.length))
const showStopAction = computed(() => props.allowStop && props.running && (props.stopping || !hasMessage.value))
const canSubmit = computed(() => Boolean(
  props.currentSession
  && !props.disabled
  && hasMessage.value
  && !props.addingImages
  && !props.submittingInput
  && !props.stopping
  && (props.allowQueue || !props.running || (props.stopReady && props.activeRunId))
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

// Keep the reading position while the mounted panel is hidden by its parent.
watch(() => props.active, async (active) => {
  if (!active) {
    if (messagesEl.value) lastScrollTop = messagesEl.value.scrollTop
    if (scrollFrame !== null) window.cancelAnimationFrame(scrollFrame)
    scrollFrame = null
    return
  }
  await nextTick()
  const el = messagesEl.value
  if (!props.active || !el) return
  el.scrollTop = autoFollowMessages.value ? el.scrollHeight : lastScrollTop
  lastScrollTop = el.scrollTop
})

onBeforeUnmount(() => {
  if (scrollFrame !== null) window.cancelAnimationFrame(scrollFrame)
})

function submit() {
  if (!canSubmit.value) return
  autoFollowMessages.value = true
  scrollMessagesToBottom({ force: true })
  emit('send')
}

function handleKeydown(event) {
  if (event.key !== 'Enter') return
  if (event.shiftKey || event.isComposing || event.keyCode === 229) return
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
  if (!props.allowImages) return
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
  if (!props.active || !el) return
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
  if (!props.active) return
  if (!options.force && !autoFollowMessages.value) return
  if (scrollFrame) return
  scrollFrame = window.requestAnimationFrame(async () => {
    scrollFrame = null
    await nextTick()
    const el = messagesEl.value
    if (!props.active || !el) return
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

<style scoped>
.failed-turn-actions { display: flex; align-items: center; gap: 12px; padding: 0 12px 16px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
