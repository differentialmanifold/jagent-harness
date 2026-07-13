<template>
  <el-tooltip
    v-if="visible"
    popper-class="context-usage-tooltip"
    placement="top"
    effect="dark"
  >
    <button
      class="context-usage-indicator"
      type="button"
      :aria-label="tooltipTitle"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle class="context-usage-track" cx="12" cy="12" r="8.5" />
        <circle
          class="context-usage-progress"
          cx="12"
          cy="12"
          r="8.5"
          :style="progressStyle"
        />
      </svg>
    </button>
    <template #content>
      <div class="context-usage-popover">
        <div class="context-usage-title">Next context baseline</div>
        <div class="context-usage-primary">{{ percentText }} used ({{ remainingText }} left)</div>
        <div class="context-usage-primary">{{ formatTokens(usedTokens) }} / {{ formatTokens(contextWindowTokens) }} tokens used</div>
        <div class="context-usage-details">
          <span v-if="hasActual">Actual baseline: {{ formatTokens(usage.actualContextTokens) }}</span>
          <span>Estimated baseline: {{ formatTokens(usage.estimatedTokens) }}</span>
          <span v-if="hasActual">Prompt: {{ formatTokens(usage.promptTokens) }}</span>
          <span v-if="hasActual">Completion: {{ formatTokens(usage.completionTokens) }}</span>
          <span v-if="hasActual">Reasoning: {{ formatTokens(usage.reasoningTokens) }}</span>
          <span v-if="hasActual">Cached: {{ formatTokens(usage.cachedTokens) }}</span>
          <span>{{ sourceLabel }}</span>
        </div>
      </div>
    </template>
  </el-tooltip>
</template>

<script setup>
import { computed } from 'vue'
import { ElTooltip } from 'element-plus'

const props = defineProps({
  usage: { type: Object, default: null }
})

const radius = 8.5
const circumference = 2 * Math.PI * radius

const visible = computed(() => Boolean(props.usage && contextWindowTokens.value > 0 && usedTokens.value > 0))
const usage = computed(() => props.usage || {})
const contextWindowTokens = computed(() => positiveNumber(usage.value.contextWindowTokens))
const hasActual = computed(() => positiveNumber(usage.value.actualContextTokens) > 0)
const usedTokens = computed(() => {
  const actual = positiveNumber(usage.value.actualContextTokens)
  if (actual > 0) return actual
  return positiveNumber(usage.value.estimatedTokens)
})
const percentage = computed(() => {
  if (!contextWindowTokens.value || !usedTokens.value) return 0
  return Math.max(0, Math.min(100, Math.round((usedTokens.value / contextWindowTokens.value) * 100)))
})
const progressStyle = computed(() => ({
  strokeDasharray: `${circumference}px`,
  strokeDashoffset: `${circumference * (1 - percentage.value / 100)}px`
}))
const percentText = computed(() => `${percentage.value}%`)
const remainingText = computed(() => `${Math.max(0, 100 - percentage.value)}%`)
const tooltipTitle = computed(() => `Context window ${percentText.value} used`)
const sourceLabel = computed(() => 'Estimated independently from the full context')

function positiveNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : 0
}

function formatTokens(value) {
  const number = positiveNumber(value)
  if (!number) return '-'
  if (number >= 1000000) return `${trimDecimal(number / 1000000)}m`
  if (number >= 1000) return `${trimDecimal(number / 1000)}k`
  return `${number}`
}

function trimDecimal(value) {
  const rounded = value >= 10 ? Math.round(value) : Math.round(value * 10) / 10
  return Number.isInteger(rounded) ? `${rounded}` : `${rounded.toFixed(1)}`
}
</script>
