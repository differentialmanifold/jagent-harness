<template>
  <section class="chat-panel">
    <header class="panel-header">
      <div>
        <h2>{{ currentSession ? currentSession.title : 'No session selected' }}</h2>
        <p>{{ statusText }}</p>
      </div>
      <div class="status-pill" :class="{ warn: provider && !provider.apiKeyConfigured }">
        {{ provider && provider.apiKeyConfigured ? 'API key ready' : 'API key missing' }}
      </div>
    </header>

    <div class="messages" ref="messagesEl">
      <MessageItem
        v-for="message in messages"
        :key="message.messageId"
        :message="message"
      />
    </div>

    <form class="composer" @submit.prevent="submit">
      <textarea
        :value="draft"
        :disabled="!currentSession || running"
        placeholder="Ask the agent to inspect, edit, or plan work..."
        @input="$emit('update:draft', $event.target.value)"
        @keydown="handleKeydown"
      ></textarea>
      <button :disabled="!currentSession || running || !draft.trim()" type="submit">
        {{ running ? 'Running' : 'Send' }}
      </button>
    </form>
  </section>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import MessageItem from './MessageItem.vue'

const props = defineProps({
  currentSession: { type: Object, default: null },
  messages: { type: Array, required: true },
  provider: { type: Object, default: null },
  statusText: { type: String, required: true },
  running: { type: Boolean, required: true },
  draft: { type: String, required: true }
})

const emit = defineEmits(['update:draft', 'send'])
const messagesEl = ref(null)
let scrollFrame = null

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
