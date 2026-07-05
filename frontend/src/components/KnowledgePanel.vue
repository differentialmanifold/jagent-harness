<template>
  <section class="knowledge-panel github-panel">
    <div :class="['knowledge-workspace', 'github-workspace', { 'prompt-workspace': !isSkillsMode }]">
      <aside v-if="isSkillsMode" class="vfs-explorer github-file-tree">
        <div class="vfs-sidebar-header github-tree-header">
          <div class="github-tree-title">
            <el-icon><Folder /></el-icon>
            <span>Files</span>
          </div>
          <el-input
            v-if="isSkillsMode"
            v-model="skillSearch"
            class="vfs-search"
            clearable
            placeholder="Filter files"
            spellcheck="false"
          />
        </div>

        <div v-if="loading" class="empty-note">Loading files</div>
        <el-empty v-else-if="treeData.length === 0" description="No files" :image-size="72" />
        <el-tree
          v-else
          ref="treeRef"
          class="vfs-tree github-tree"
          node-key="path"
          :data="treeData"
          :props="treeProps"
          :highlight-current="true"
          :expand-on-click-node="false"
          default-expand-all
          @node-click="handleTreeNodeClick"
        >
          <template #default="{ data }">
            <span :class="['vfs-tree-node github-tree-node', data.type]">
              <el-icon>
                <Folder v-if="data.type === 'dir'" />
                <Document v-else />
              </el-icon>
              <span class="vfs-tree-label">{{ data.label }}</span>
            </span>
          </template>
        </el-tree>
      </aside>

      <main class="github-content-pane">
        <header class="github-pathbar">
          <nav
            v-if="!isSkillsMode"
            class="github-breadcrumbs"
            aria-label="Path"
          >
            <span class="github-breadcrumb current">AGENTS.md</span>
          </nav>

          <nav
            v-else
            class="github-breadcrumbs"
            aria-label="Path"
            :title="displayPath"
          >
            <template v-for="(crumb, index) in breadcrumbs" :key="crumb.path">
              <button
                :class="[
                  'github-breadcrumb',
                  {
                    root: index === 0,
                    current: index === breadcrumbs.length - 1
                  }
                ]"
                type="button"
                :title="crumb.path"
                @click="selectPath(crumb.path)"
              >
                {{ crumb.label }}
              </button>
              <span
                v-if="index < breadcrumbs.length - 1"
                class="github-breadcrumb-separator"
                aria-hidden="true"
              >
                /
              </span>
            </template>
            <span v-if="displayPath.endsWith('/')" class="github-path-slash">/</span>
          </nav>

          <el-button
            v-if="isSkillsMode"
            :icon="CopyDocument"
            circle
            title="Copy path"
            @click="copyPath"
          />

          <div v-if="showFileActions" class="github-path-actions">
            <el-dropdown v-if="isSkillsMode" trigger="click" @command="handleAddFileCommand">
              <el-button>
                Add file
                <el-icon class="el-icon--right"><CaretBottom /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="new-skill">New skill</el-dropdown-item>
                  <el-dropdown-item command="new-file">New file</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>

            <el-upload
              v-if="isSkillsMode"
              accept=".zip,application/zip"
              :show-file-list="false"
              :auto-upload="false"
              :on-change="importSkills"
            >
              <el-button :icon="Upload" :loading="importing">Import zip</el-button>
            </el-upload>

            <el-button v-if="isSkillsMode" :icon="Download" :loading="exporting" @click="exportSkills">
              Export zip
            </el-button>

            <el-button
              v-if="showEditButton"
              :icon="EditPen"
              :disabled="loading"
              @click="beginEditFile"
            >
              Edit
            </el-button>

            <el-popconfirm
              v-if="canDeleteCurrent"
              width="300"
              :title="deleteConfirmTitle"
              confirm-button-text="Delete"
              cancel-button-text="Cancel"
              @confirm="deleteCurrent"
            >
              <template #reference>
                <el-button type="danger" plain :icon="Delete" :loading="deleting">Delete</el-button>
              </template>
            </el-popconfirm>
          </div>
        </header>

        <section
          v-if="!isEditing && (selectedKind === 'dir' || selectedFile)"
          class="github-commit-row"
        >
          <div class="github-commit-avatar">DB</div>
          <strong>{{ commitTitle }}</strong>
          <span>{{ commitSubtitle }}</span>
          <span v-if="currentUpdatedAt" class="github-commit-date">updated {{ formatDate(currentUpdatedAt) }}</span>
        </section>

        <section v-if="isEditing" class="github-editor-view">
          <div class="github-file-toolbar">
            <el-radio-group v-model="editTab" size="small">
              <el-radio-button value="edit">Edit</el-radio-button>
              <el-radio-button value="preview">Preview</el-radio-button>
            </el-radio-group>
            <span>{{ editLineCount }} lines</span>
          </div>

          <el-form class="github-edit-path" label-position="top" @submit.prevent>
            <el-form-item label="Path" :error="editPathValidationMessage">
              <el-input v-model="editDraftPath" spellcheck="false" />
            </el-form-item>
          </el-form>

          <div v-if="editTab === 'preview'" class="github-markdown-body">
            <template v-if="editPreviewBlocks.length">
              <component
                :is="block.tag"
                v-for="(block, index) in editPreviewBlocks"
                :key="index"
                :class="block.className"
              >
                {{ block.text }}
              </component>
            </template>
            <p v-else class="empty-note">No preview</p>
          </div>

          <el-input
            v-else
            ref="editorTextarea"
            v-model="editDraftContent"
            class="code-editor github-code-editor"
            type="textarea"
            resize="none"
            spellcheck="false"
            @keydown="handleEditorKeydown"
          />

          <footer class="github-commit-box">
            <div class="github-commit-actions">
              <el-button :disabled="saving" @click="cancelEdit">Cancel</el-button>
              <el-button
                type="primary"
                :icon="Check"
                :loading="saving"
                :disabled="!canSave"
                @click="saveChanges"
              >
                Save changes
              </el-button>
            </div>
            <span v-if="notice" class="success">{{ notice }}</span>
            <span v-if="error" class="error">{{ error }}</span>
          </footer>
        </section>

        <section v-else-if="selectedKind === 'dir'" class="github-directory-view">
          <table class="github-file-list-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Last updated</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in directoryRows" :key="row.path">
                <td>
                  <button
                    :class="['github-file-row-button', row.type]"
                    type="button"
                    @click="selectPath(row.path)"
                  >
                    <el-icon>
                      <Folder v-if="row.type === 'dir'" />
                      <Document v-else />
                    </el-icon>
                    <span>{{ row.name }}</span>
                  </button>
                </td>
                <td>{{ row.type === 'dir' ? 'Directory' : 'File' }}</td>
                <td>{{ row.updatedAt ? formatDate(row.updatedAt) : '-' }}</td>
              </tr>
            </tbody>
          </table>
          <el-empty v-if="directoryRows.length === 0" description="No files" :image-size="80" />
        </section>

        <section v-else-if="selectedFile" class="github-file-view">
          <div class="github-file-toolbar">
            <el-radio-group v-model="fileViewTab" size="small">
              <el-radio-button value="preview" :disabled="!isMarkdownFile">Preview</el-radio-button>
              <el-radio-button value="code">Code</el-radio-button>
            </el-radio-group>
            <span>{{ fileLineCount }} lines</span>
            <span>{{ selectedFile ? selectedFile.bytes : 0 }} bytes</span>
            <div class="github-file-toolbar-actions">
              <el-button size="small" :icon="CopyDocument" @click="copyContent" />
            </div>
          </div>

          <div v-if="fileViewTab === 'preview' && isMarkdownFile" class="github-markdown-body">
            <template v-if="filePreviewBlocks.length">
              <component
                :is="block.tag"
                v-for="(block, index) in filePreviewBlocks"
                :key="index"
                :class="block.className"
              >
                {{ block.text }}
              </component>
            </template>
            <p v-else class="empty-note">No preview</p>
          </div>
          <pre v-else class="github-code-view"><code>{{ fileContent }}</code></pre>
        </section>

        <section v-else class="github-empty-file-view">
          <el-empty :description="`No ${scopeLabel} AGENTS.md`">
            <el-button type="primary" :icon="Plus" @click="beginEditFile">
              Create AGENTS.md
            </el-button>
          </el-empty>
        </section>
      </main>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElRadioButton, ElRadioGroup } from 'element-plus'
import {
  CaretBottom,
  Check,
  CopyDocument,
  Delete,
  Document,
  Download,
  EditPen,
  Folder,
  Plus,
  Upload
} from '@element-plus/icons-vue'
import { request } from '../api/http'
import { formatDate } from '../utils/format'
import { countLines, markdownBlocks } from '../utils/markdown'

const props = defineProps({
  mode: {
    type: String,
    required: true,
    validator: (value) => ['prompts', 'skills'].includes(value)
  },
  sessionId: { type: String, default: '' },
  scope: {
    type: String,
    default: 'global',
    validator: (value) => ['global', 'project'].includes(value)
  }
})

const emit = defineEmits(['changed'])

const treeProps = { children: 'children', label: 'label' }
const isSkillsMode = computed(() => props.mode === 'skills')
const rootPath = computed(() => isSkillsMode.value ? 'skills' : 'AGENTS.md')

const files = ref([])
const selectedPath = ref(rootPath.value)
const fileContent = ref('')
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const importing = ref(false)
const exporting = ref(false)
const error = ref('')
const notice = ref('')
const skillSearch = ref('')
const fileViewTab = ref('preview')
const editTab = ref('edit')
const editMode = ref('')
const editOriginalPath = ref('')
const editDraftPath = ref('')
const editDraftContent = ref('')
const treeRef = ref(null)
const editorTextarea = ref(null)

const scopeLabel = computed(() => props.scope === 'project' ? 'project' : 'global')

const visibleFiles = computed(() => {
  return files.value
    .filter((file) => isPathInMode(file.path))
    .slice()
    .sort((left, right) => left.path.localeCompare(right.path))
})

const fileByPath = computed(() => {
  const map = new Map()
  for (const file of visibleFiles.value) {
    map.set(file.path, file)
  }
  return map
})

const skillKeys = computed(() => {
  const keys = new Set()
  for (const file of visibleFiles.value) {
    const parts = file.path.split('/')
    if (parts.length >= 3 && parts[0] === 'skills' && parts[1]) {
      keys.add(parts[1])
    }
  }
  return Array.from(keys).sort()
})

const treeData = computed(() => {
  if (!isSkillsMode.value) {
    return [{
      label: 'AGENTS.md',
      path: 'AGENTS.md',
      type: 'file',
      children: []
    }]
  }

  const root = createTreeNode('skills', 'skills', 'dir')
  const query = skillSearch.value.trim().toLowerCase()
  for (const file of visibleFiles.value) {
    if (query && !file.path.toLowerCase().includes(query)) {
      continue
    }
    addPath(root, file.path)
  }
  return [sortTreeNode(root)]
})

const nodeByPath = computed(() => {
  const map = new Map()
  for (const node of treeData.value) {
    collectTreeNodes(node, map)
  }
  return map
})

const selectedNode = computed(() => nodeByPath.value.get(selectedPath.value) || null)
const selectedFile = computed(() => fileByPath.value.get(selectedPath.value) || null)
const selectedKind = computed(() => {
  if (!isSkillsMode.value) return 'file'
  return selectedNode.value ? selectedNode.value.type : 'dir'
})
const isEditing = computed(() => Boolean(editMode.value))
const showFileActions = computed(() => true)
const displayPath = computed(() => {
  if (isEditing.value) return normalizeDraftPath(editDraftPath.value) || editDraftPath.value || rootPath.value
  return selectedPath.value
})
const breadcrumbs = computed(() => createBreadcrumbs(displayPath.value, selectedKind.value))
const selectedDirectory = computed(() => selectedKind.value === 'dir' ? selectedPath.value : parentPath(selectedPath.value))
const showEditButton = computed(() => !isEditing.value && selectedKind.value === 'file' && Boolean(selectedFile.value))
const canDeleteCurrent = computed(() => {
  if (isEditing.value || deleting.value) return false
  if (selectedKind.value === 'file') return Boolean(selectedFile.value)
  return isSkillsMode.value && selectedPath.value !== 'skills' && filesUnderDirectory(selectedPath.value).length > 0
})
const deleteConfirmTitle = computed(() => {
  return selectedKind.value === 'dir'
    ? `Delete ${selectedPath.value} and all files under it?`
    : `Delete ${selectedPath.value}?`
})
const currentUpdatedAt = computed(() => {
  if (selectedKind.value === 'file') return selectedFile.value ? selectedFile.value.updatedAt : ''
  const rows = directoryRows.value.filter((row) => row.updatedAt)
  return rows.length ? rows[0].updatedAt : ''
})
const commitTitle = computed(() => selectedKind.value === 'dir' ? 'Directory snapshot' : `${scopeLabel.value} file`)
const commitSubtitle = computed(() => {
  return selectedKind.value === 'dir'
    ? selectedPath.value
    : selectedFile.value?.name || ''
})
const isMarkdownFile = computed(() => isMarkdownPath(selectedPath.value))
const fileLineCount = computed(() => countLines(fileContent.value))
const editLineCount = computed(() => countLines(editDraftContent.value))
const filePreviewBlocks = computed(() => markdownBlocks(fileContent.value))
const editPreviewBlocks = computed(() => markdownBlocks(editDraftContent.value))
const editPathValidationMessage = computed(() => validationMessage(normalizeDraftPath(editDraftPath.value)))
const canSave = computed(() => {
  return Boolean(normalizeDraftPath(editDraftPath.value))
    && !editPathValidationMessage.value
    && !saving.value
})

const directoryRows = computed(() => {
  if (!isSkillsMode.value || selectedKind.value !== 'dir') return []
  const dir = normalizeDraftPath(selectedPath.value)
  const rows = new Map()
  if (dir !== 'skills') {
    rows.set(parentPath(dir), {
      name: '..',
      path: parentPath(dir) || 'skills',
      type: 'dir',
      updatedAt: ''
    })
  }
  for (const file of visibleFiles.value) {
    if (!isDirectOrNestedChild(file.path, dir)) continue
    const relative = file.path.slice(dir.length + 1)
    if (!relative) continue
    const parts = relative.split('/')
    const childName = parts[0]
    const childPath = `${dir}/${childName}`
    const existing = rows.get(childPath)
    const childType = parts.length === 1 ? 'file' : 'dir'
    rows.set(childPath, {
      name: childName,
      path: childPath,
      type: existing && existing.type === 'dir' ? 'dir' : childType,
      updatedAt: newestDate(existing ? existing.updatedAt : '', file.updatedAt)
    })
  }
  return Array.from(rows.values()).sort((left, right) => {
    if (left.name === '..') return -1
    if (right.name === '..') return 1
    if (left.type !== right.type) return left.type === 'dir' ? -1 : 1
    return left.name.localeCompare(right.name)
  })
})

watch(
  () => props.mode,
  async () => {
    selectedPath.value = rootPath.value
    cancelEdit()
    await loadFiles()
  }
)

watch(
  () => [props.sessionId, props.scope],
  async () => {
    cancelEdit()
    selectedPath.value = rootPath.value
    await loadFiles()
  }
)

onMounted(loadFiles)

async function loadFiles() {
  loading.value = true
  error.value = ''
  try {
    files.value = await request(scopedVfsUrl('/api/vfs/files', { prefix: listPrefix() }))
    await nextTick()
    if (isSkillsMode.value) {
      if (!nodeByPath.value.has(selectedPath.value)) {
        selectedPath.value = 'skills'
      }
      if (selectedKind.value === 'file') {
        await loadContent(selectedPath.value)
      } else {
        fileContent.value = ''
      }
    } else {
      selectedPath.value = 'AGENTS.md'
      if (fileByPath.value.has('AGENTS.md')) {
        await loadContent('AGENTS.md')
      } else {
        fileContent.value = ''
      }
    }
    setCurrentTreeKey()
  } catch (err) {
    showError(err)
  } finally {
    loading.value = false
  }
}

async function handleTreeNodeClick(data) {
  await selectPath(data.path)
}

async function selectPath(path) {
  const normalizedPath = normalizeDraftPath(path)
  if (!normalizedPath) return
  cancelEdit()
  selectedPath.value = normalizedPath
  error.value = ''
  notice.value = ''
  if (fileByPath.value.has(normalizedPath)) {
    await loadContent(normalizedPath)
    if (!isMarkdownPath(normalizedPath)) {
      fileViewTab.value = 'code'
    }
  } else {
    fileContent.value = ''
  }
  await nextTick()
  setCurrentTreeKey()
}

async function loadContent(path) {
  const file = await request(scopedVfsUrl('/api/vfs/files/content', { path }))
  selectedPath.value = file.path
  fileContent.value = file.content || ''
}

function beginEditFile() {
  editMode.value = selectedFile.value ? 'edit' : 'create'
  editOriginalPath.value = selectedPath.value
  editDraftPath.value = selectedPath.value
  editDraftContent.value = fileContent.value
  editTab.value = 'edit'
  notice.value = ''
  error.value = ''
}

function beginCreateSkill() {
  const path = nextSkillPath()
  beginCreateFile(path, defaultSkillContent(path))
}

function beginCreateLinkedFile() {
  const path = nextLinkedFilePath()
  beginCreateFile(path, '')
}

function beginCreateFile(path, content) {
  editMode.value = 'create'
  editOriginalPath.value = ''
  editDraftPath.value = path
  editDraftContent.value = content
  editTab.value = 'edit'
  notice.value = ''
  error.value = ''
}

function cancelEdit() {
  editMode.value = ''
  editOriginalPath.value = ''
  editDraftPath.value = ''
  editDraftContent.value = ''
  editTab.value = 'edit'
}

function hasUnsavedChanges() {
  if (!isEditing.value) return false
  if (editMode.value === 'create') return true
  return normalizeDraftPath(editDraftPath.value) !== normalizeDraftPath(editOriginalPath.value)
    || editDraftContent.value !== fileContent.value
}

async function saveChanges() {
  if (!canSave.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const path = normalizeDraftPath(editDraftPath.value)
    const saved = await request(scopedVfsUrl('/api/vfs/files'), {
      method: 'PUT',
      body: JSON.stringify({
        path,
        content: editDraftContent.value,
        contentType: contentType(path)
      })
    })
    if (editMode.value === 'edit'
        && editOriginalPath.value
        && editOriginalPath.value !== saved.path
        && fileByPath.value.has(editOriginalPath.value)) {
      await request(scopedVfsUrl('/api/vfs/files', { path: editOriginalPath.value }), {
        method: 'DELETE'
      })
    }
    cancelEdit()
    await loadFiles()
    await selectPath(saved.path)
    notice.value = `Saved ${saved.path}.`
    ElMessage.success(notice.value)
    emit('changed')
  } catch (err) {
    showError(err)
  } finally {
    saving.value = false
  }
}

async function deleteCurrent() {
  if (!canDeleteCurrent.value) return
  deleting.value = true
  error.value = ''
  notice.value = ''
  try {
    const paths = selectedKind.value === 'dir'
      ? filesUnderDirectory(selectedPath.value).map((file) => file.path)
      : [selectedPath.value]
    for (const path of paths) {
      await request(scopedVfsUrl('/api/vfs/files', { path }), {
        method: 'DELETE'
      })
    }
    const nextPath = selectedKind.value === 'dir' ? parentPath(selectedPath.value) || 'skills' : selectedDirectory.value || rootPath.value
    await loadFiles()
    await selectPath(nextPath)
    notice.value = `Deleted ${paths.length} file${paths.length === 1 ? '' : 's'}.`
    ElMessage.success(notice.value)
    emit('changed')
  } catch (err) {
    showError(err)
  } finally {
    deleting.value = false
  }
}

function handleAddFileCommand(command) {
  if (command === 'new-skill') {
    beginCreateSkill()
    return
  }
  if (command === 'new-file') {
    beginCreateLinkedFile()
  }
}

async function importSkills(uploadFile) {
  const file = uploadFile && uploadFile.raw
  if (!file || importing.value) return
  importing.value = true
  error.value = ''
  notice.value = ''
  try {
    const body = new FormData()
    body.append('file', file)
    const result = await fetchJson(scopedVfsUrl('/api/vfs/skills/import'), {
      method: 'POST',
      body
    })
    await loadFiles()
    notice.value = `Imported ${result.imported || 0} files.`
    ElMessage.success(notice.value)
    emit('changed')
  } catch (err) {
    showError(err)
  } finally {
    importing.value = false
  }
}

async function exportSkills() {
  if (exporting.value) return
  exporting.value = true
  error.value = ''
  notice.value = ''
  try {
    const response = await fetch(scopedVfsUrl('/api/vfs/skills/export'))
    if (!response.ok) {
      throw new Error(await responseError(response))
    }
    const blob = await response.blob()
    downloadBlob(blob, 'skills.zip')
    notice.value = 'Exported skills.zip.'
    ElMessage.success(notice.value)
  } catch (err) {
    showError(err)
  } finally {
    exporting.value = false
  }
}

function handleEditorKeydown(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    saveChanges()
    return
  }
  if (event.key !== 'Tab') return
  event.preventDefault()
  const target = event.target
  const start = target.selectionStart
  const end = target.selectionEnd
  editDraftContent.value = `${editDraftContent.value.slice(0, start)}  ${editDraftContent.value.slice(end)}`
  window.requestAnimationFrame(() => {
    const textarea = editorTextarea.value?.textarea
    if (textarea) {
      textarea.selectionStart = start + 2
      textarea.selectionEnd = start + 2
    }
  })
}

async function copyPath() {
  await writeClipboard(displayPath.value)
  ElMessage.success('Copied path.')
}

async function copyContent() {
  await writeClipboard(fileContent.value)
  ElMessage.success('Copied content.')
}

async function writeClipboard(value) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    await navigator.clipboard.writeText(value)
  }
}

function listPrefix() {
  return isSkillsMode.value ? 'skills' : 'AGENTS.md'
}

function scopedVfsUrl(path, values = {}) {
  const query = new URLSearchParams()
  query.set('scope', props.scope)
  if (props.scope === 'project' && props.sessionId) query.set('sessionId', props.sessionId)
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  }
  return `${path}?${query.toString()}`
}

function isPathInMode(path) {
  const normalizedPath = normalizeDraftPath(path)
  if (!normalizedPath) return false
  return isSkillsMode.value ? normalizedPath.startsWith('skills/') : normalizedPath === 'AGENTS.md'
}

function validationMessage(path) {
  if (!path) return ''
  if (path === 'SYSTEM.md') return 'SYSTEM.md is built in and cannot be saved here.'
  if (path.split('/').some((part) => !part || part === '.' || part === '..')) return 'Path has an invalid segment.'
  if (isSkillsMode.value) {
    if (!path.startsWith('skills/')) return 'Skills must be saved under skills/.'
    if (path.split('/').length < 3) return 'Skill files must be under skills/{skill}/.'
    if (fileName(path) === 'SKILL.md' && path.split('/').length !== 3) return 'SKILL.md must be directly under skills/{skill}/.'
    return ''
  }
  if (path !== 'AGENTS.md') return 'Prompt management saves AGENTS.md only.'
  return ''
}

function normalizeDraftPath(path) {
  return String(path || '')
    .trim()
    .replace(/\\/g, '/')
    .replace(/\/+/g, '/')
    .replace(/^\/+|\/+$/g, '')
}

function createTreeNode(label, path, type) {
  return {
    label,
    path,
    type,
    children: []
  }
}

function addPath(root, path) {
  const segments = path.split('/').filter(Boolean)
  let cursor = root
  for (let index = 1; index < segments.length; index += 1) {
    const name = segments[index]
    const isFile = index === segments.length - 1
    const childPath = segments.slice(0, index + 1).join('/')
    let child = cursor.children.find((item) => item.path === childPath)
    if (!child) {
      child = createTreeNode(name, childPath, isFile ? 'file' : 'dir')
      cursor.children.push(child)
    }
    if (isFile) {
      child.type = 'file'
    }
    cursor = child
  }
}

function sortTreeNode(node) {
  return {
    ...node,
    children: node.children
      .slice()
      .sort((left, right) => {
        if (left.type !== right.type) return left.type === 'dir' ? -1 : 1
        return left.label.localeCompare(right.label)
      })
      .map(sortTreeNode)
  }
}

function collectTreeNodes(node, map) {
  map.set(node.path, node)
  for (const child of node.children || []) {
    collectTreeNodes(child, map)
  }
}

function createBreadcrumbs(path, kind) {
  const normalizedPath = normalizeDraftPath(path)
  if (!normalizedPath) return []
  const parts = normalizedPath.split('/')
  const crumbs = []
  for (let index = 0; index < parts.length; index += 1) {
    const crumbPath = parts.slice(0, index + 1).join('/')
    const isLast = index === parts.length - 1
    crumbs.push({
      label: parts[index],
      path: kind === 'file' && isLast ? normalizedPath : crumbPath
    })
  }
  return crumbs
}

function isDirectOrNestedChild(path, directory) {
  return path !== directory && path.startsWith(`${directory}/`)
}

function filesUnderDirectory(directory) {
  const normalizedDirectory = normalizeDraftPath(directory)
  return visibleFiles.value.filter((file) => isDirectOrNestedChild(file.path, normalizedDirectory))
}

function newestDate(left, right) {
  if (!left) return right || ''
  if (!right) return left
  return String(right) > String(left) ? right : left
}

function nextSkillPath() {
  let index = 1
  while (true) {
    const slug = index === 1 ? 'new-skill' : `new-skill-${index}`
    const path = `skills/${slug}/SKILL.md`
    if (!fileByPath.value.has(path)) return path
    index += 1
  }
}

function nextLinkedFilePath() {
  const dir = currentAddDirectory()
  let index = 1
  while (true) {
    const name = index === 1 ? 'notes.md' : `notes-${index}.md`
    const path = `${dir}/${name}`
    if (!fileByPath.value.has(path)) return path
    index += 1
  }
}

function currentAddDirectory() {
  const dir = selectedKind.value === 'dir' ? selectedPath.value : parentPath(selectedPath.value)
  const parts = normalizeDraftPath(dir).split('/')
  if (parts.length >= 2 && parts[0] === 'skills') {
    return dir
  }
  return parentPath(nextSkillPath())
}

function defaultSkillContent(path) {
  const parts = String(path || '').split('/').filter(Boolean)
  const slug = parts.length > 1 ? parts[parts.length - 2] : 'new-skill'
  const name = slug
    .split(/[-_]/)
    .filter(Boolean)
    .map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`)
    .join(' ') || 'New Skill'
  return `---\nname: ${name}\ndescription: Use when this workflow is relevant.\n---\n\n# ${name}\n\nDescribe the workflow here.\n`
}

function setCurrentTreeKey() {
  if (treeRef.value && selectedPath.value) {
    treeRef.value.setCurrentKey(selectedPath.value)
  }
}

function parentPath(path) {
  const segments = normalizeDraftPath(path).split('/').filter(Boolean)
  if (segments.length <= 1) return ''
  return segments.slice(0, -1).join('/')
}

function fileName(path) {
  const segments = normalizeDraftPath(path).split('/').filter(Boolean)
  return segments.length ? segments[segments.length - 1] : ''
}

function isMarkdownPath(path) {
  const lower = normalizeDraftPath(path).toLowerCase()
  return lower.endsWith('.md') || lower.endsWith('.markdown')
}

function contentType(path) {
  const lower = path.toLowerCase()
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'text/markdown'
  if (lower.endsWith('.json')) return 'application/json'
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'application/yaml'
  return 'text/plain'
}

function downloadBlob(blob, fileNameValue) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileNameValue
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function showError(err) {
  error.value = err.message
  ElMessage.error(err.message)
}

async function fetchJson(url, options) {
  const response = await fetch(url, options)
  const text = await response.text()
  if (!response.ok) {
    throw new Error(await responseError(response, text))
  }
  return text ? JSON.parse(text) : {}
}

async function responseError(response, text = null) {
  const body = text == null ? await response.text() : text
  if (!body) return response.statusText
  try {
    return JSON.parse(body).message || response.statusText
  } catch {
    return body
  }
}

defineExpose({
  hasUnsavedChanges,
  discardChanges: cancelEdit
})
</script>
