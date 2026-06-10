<template>
  <aside class="inspector">
    <el-tabs class="inspector-tabs" model-value="context">
      <el-tab-pane label="Context" name="context">
        <el-scrollbar>
          <section>
            <h3>Provider</h3>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="Active">{{ provider ? provider.activeProvider : '-' }}</el-descriptions-item>
              <el-descriptions-item label="Model">{{ provider ? provider.model : '-' }}</el-descriptions-item>
              <el-descriptions-item label="Base URL">{{ provider ? provider.baseUrl : '-' }}</el-descriptions-item>
            </el-descriptions>
          </section>

          <section v-if="loadedPromptFiles.length">
            <h3>Prompt Files</h3>
            <ul class="prompt-file-list">
              <li v-for="file in loadedPromptFiles" :key="file.path || file.name" class="prompt-file">
                <header>
                  <strong>{{ file.name }}</strong>
                  <el-tag size="small" type="success">{{ file.mode }}</el-tag>
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
                  <el-tag size="small">{{ skill.scope || 'provider' }}</el-tag>
                </header>
                <span>{{ skill.description || '-' }}</span>
                <code>{{ skill.filePath || '-' }}</code>
              </li>
            </ul>
            <el-empty v-else description="No skills loaded" :image-size="72" />
          </section>
        </el-scrollbar>
      </el-tab-pane>

      <el-tab-pane label="Tools" name="tools">
        <el-scrollbar>
          <section>
            <h3>Tools</h3>
            <ul class="tool-list">
              <li v-for="tool in tools" :key="tool.name">
                <strong>{{ tool.name }}</strong>
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
