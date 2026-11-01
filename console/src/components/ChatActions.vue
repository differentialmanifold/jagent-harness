<template>
  <el-dropdown class="chat-actions" trigger="click">
    <el-button text :icon="MoreFilled" circle title="Chat actions" aria-label="Chat actions" />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item @click="$emit('rename')">Rename</el-dropdown-item>
        <el-dropdown-item :disabled="busy" divided @click="$emit('remove')">Delete</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
<script setup>
import { MoreFilled } from '@element-plus/icons-vue'
defineProps({ busy: Boolean })
defineEmits(['rename', 'remove'])
</script>
