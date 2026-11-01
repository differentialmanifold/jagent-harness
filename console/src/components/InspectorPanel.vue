<template>
  <aside class="inspector">
    <el-tabs class="inspector-tabs" v-model="activeTab">
      <el-tab-pane label="Runtime Preview" name="preview">
        <el-scrollbar>
          <section class="inspector-section">
            <p>Generated from the current settings, not a snapshot of a past model request.</p>
            <FinalPromptPanel :session-id="sessionId" />
          </section>
        </el-scrollbar>
      </el-tab-pane>
      <el-tab-pane label="Context" name="context">
        <el-scrollbar>
          <section class="inspector-section">
            <h3>Provider</h3>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="Active">{{ provider ? provider.activeProvider : '-' }}</el-descriptions-item>
              <el-descriptions-item label="Model">{{ provider ? provider.model : '-' }}</el-descriptions-item>
              <el-descriptions-item label="Base URL">{{ provider ? provider.baseUrl : '-' }}</el-descriptions-item>
            </el-descriptions>
          </section>

          <section v-if="loadedPromptFiles.length" class="inspector-section">
            <h3>Prompt Files</h3>
            <ul class="prompt-file-list">
              <li v-for="file in loadedPromptFiles" :key="file.path || file.name" class="prompt-file">
                <header>
                  <strong>{{ file.name }}</strong>
                  <el-tag size="small" type="success">{{ scopeLabel(file.mode) }}</el-tag>
                </header>
                <span>{{ file.description }}</span>
                <code>{{ file.path || agentConfigRoot }}</code>
              </li>
            </ul>
          </section>

          <section v-if="skills.length" class="inspector-section">
            <h3>Available Skills</h3>
            <ul class="skill-list">
              <li v-for="skill in skills" :key="skill.name">
                <header>
                  <strong>{{ skill.name }}</strong>
                  <el-tag size="small">{{ scopeLabel(skill.scope) }}</el-tag>
                </header>
                <span>{{ skill.description || '-' }}</span>
                <code>{{ skill.filePath || '-' }}</code>
              </li>
            </ul>
          </section>
        </el-scrollbar>
      </el-tab-pane>

      <el-tab-pane label="Tools" name="tools">
        <el-scrollbar>
          <section class="inspector-section">
            <h3>Available tools</h3>
            <ul class="tool-list">
              <li v-for="tool in tools" :key="tool.name">
                <strong>{{ tool.name }}</strong>
                <el-tag size="small">{{ tool.executionTarget === 'runner' ? 'Runner' : 'Server' }}</el-tag>
                <details><summary>Input schema</summary><pre>{{ JSON.stringify(tool.parametersSchema, null, 2) }}</pre></details>
                <span>{{ tool.description }}</span>
              </li>
            </ul>
          </section>
        </el-scrollbar>
      </el-tab-pane>
    </el-tabs>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'
import FinalPromptPanel from './FinalPromptPanel.vue'

const activeTab = ref('preview')

const props = defineProps({
  sessionId: { type: String, required: true },
  provider: { type: Object, default: null },
  tools: { type: Array, required: true },
  agentContext: { type: Object, default: null }
})

const scopeLabel = scope => ({ global: 'Project defaults', 'agent-global': 'Agent defaults', project: 'Project', agent: 'Agent' })[scope] || scope || 'Provider'

const promptFiles = computed(() => props.agentContext?.promptFiles || [])
const loadedPromptFiles = computed(() => promptFiles.value.filter((file) => file.exists))
const skills = computed(() => props.agentContext?.skills || [])
const agentConfigRoot = computed(() => props.agentContext?.configRoot || '-')
</script>
