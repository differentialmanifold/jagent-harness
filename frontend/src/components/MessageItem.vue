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
        <span v-if="durationText" class="message-duration">
          {{ durationLabel }} {{ durationText }}
        </span>
      </div>
      <div
        v-if="message.thinking && !message.content && !message.reasoningContent && !messageImages.length"
        class="assistant-thinking"
        aria-live="polite"
      >
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>Thinking</span>
        <span class="activity-dots" aria-hidden="true"><i></i><i></i><i></i></span>
      </div>
      <div
        v-if="messageImages.length"
        :class="['message-images', { single: messageImages.length === 1 }]"
      >
        <el-image
          v-for="(image, index) in messageImages"
          :key="`${image.name || 'image'}:${index}`"
          class="message-image"
          :src="image.url"
          :alt="image.name || `Image ${index + 1}`"
          :preview-src-list="imageUrls"
          :initial-index="index"
          fit="cover"
          lazy
          preview-teleported
          hide-on-click-modal
          referrerpolicy="no-referrer"
        />
      </div>
      <div v-if="message.reasoningContent" class="message-reasoning">
        <div class="message-reasoning-label">Reasoning</div>
        <pre><span>{{ message.reasoningContent }}</span><span
          v-if="message.streaming && !message.content"
          class="stream-cursor"
          aria-hidden="true"
        ></span></pre>
      </div>
      <pre v-else-if="message.content"><span>{{ message.content }}</span><span
        v-if="message.streaming"
        class="stream-cursor"
        aria-hidden="true"
      ></span></pre>
      <pre v-if="message.reasoningContent && message.content"><span>{{ message.content }}</span><span
        v-if="message.streaming"
        class="stream-cursor"
        aria-hidden="true"
      ></span></pre>
    </template>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { ElImage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ToolMessage from './ToolMessage.vue'
import { formatDuration } from '../utils/format'
import { messageClass } from '../utils/toolDisplay'
import { isDisplayableImageUrl } from '../utils/images'

const props = defineProps({
  message: { type: Object, required: true }
})

defineEmits(['resolveToolApproval'])

const messageImages = computed(() => (
  (Array.isArray(props.message.images) ? props.message.images : [])
    .filter((image) => image && isDisplayableImageUrl(image.url))
))
const imageUrls = computed(() => messageImages.value.map((image) => image.url))

const durationText = computed(() => (
  formatDuration(props.message.runDurationMillis)
))
const durationLabel = computed(() => {
  if (props.message.stopReason === 'aborted') return 'Stopped after'
  if (props.message.failed) return 'Failed after'
  return 'Took'
})
</script>
