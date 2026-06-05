<template>
  <aside class="inspector">
    <section>
      <h3>Provider</h3>
      <dl>
        <dt>Active</dt>
        <dd>{{ provider ? provider.activeProvider : '-' }}</dd>
        <dt>Model</dt>
        <dd>{{ provider ? provider.model : '-' }}</dd>
        <dt>Base URL</dt>
        <dd>{{ provider ? provider.baseUrl : '-' }}</dd>
      </dl>
    </section>

    <section v-if="loadedPromptFiles.length">
      <h3>Prompt Files</h3>
      <ul class="prompt-file-list">
        <li v-for="file in loadedPromptFiles" :key="file.name" class="prompt-file">
          <header>
            <strong>{{ file.name }}</strong>
            <small class="active">{{ file.mode }}</small>
          </header>
          <span>{{ file.description }}</span>
          <code>{{ file.path || agentConfigRoot }}</code>
        </li>
      </ul>
    </section>

    <section>
      <h3>Skills</h3>
      <ul v-if="skills.length" class="skill-list">
        <li v-for="skill in skills" :key="skill.name">
          <header>
            <strong>{{ skill.name }}</strong>
            <small>{{ skill.scope || 'provider' }}</small>
          </header>
          <span>{{ skill.description || '-' }}</span>
          <code>{{ skill.filePath || skill.directoryPath || '-' }}</code>
        </li>
      </ul>
      <p v-else class="empty-note">No skills loaded</p>
    </section>

    <section>
      <h3>Tools</h3>
      <ul class="tool-list">
        <li v-for="tool in tools" :key="tool.name">
          <strong>{{ tool.name }}</strong>
          <span>{{ tool.description }}</span>
        </li>
      </ul>
    </section>
  </aside>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  provider: { type: Object, default: null },
  tools: { type: Array, required: true },
  agentContext: { type: Object, default: null }
})

const promptFiles = computed(() => props.agentContext?.promptFiles || [])
const loadedPromptFiles = computed(() => promptFiles.value.filter((file) => file.exists))
const skills = computed(() => props.agentContext?.skills || [])
const agentConfigRoot = computed(() => props.agentContext?.configRoot || '-')
</script>
