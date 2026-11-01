<template>
  <div class="chat-markdown" v-html="rendered"></div>
</template>

<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown'
const props = defineProps({ content: { type: String, default: '' } })
const rendered = computed(() => renderMarkdown(props.content))
</script>
