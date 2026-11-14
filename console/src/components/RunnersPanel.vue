<template>
  <section class="runners-settings">
    <el-button @click="load">Refresh</el-button
    ><el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-empty
      v-if="!runners.length"
      description="Start a Runner to make an execution environment available."
    />
    <article v-for="runner in runners" :key="runner.runnerId">
      <header>
        <h3>{{ runner.name }}</h3>
        <el-tag v-if="runner.runnerId === primaryRunnerId" effect="plain">Primary Runner</el-tag>
        <el-tag :type="runner.online ? 'success' : 'info'">{{
          runner.online ? "Online" : "Offline · jobs wait for reconnect"
        }}</el-tag>
      </header>
      <p v-if="runner.runnerId === primaryRunnerId">Bound to your primary Agent. Change the primary Agent in its Configuration to select another default environment.</p>
      <p>{{ runner.platform || runner.runtime }}</p>
      <p>
        {{
          runner.description ||
          "No capability description supplied by this Runner."
        }}
      </p>
      <div>
        <el-tag
          v-for="capability in runner.capabilities"
          :key="capability"
          effect="plain"
          >{{ capability }}</el-tag
        >
      </div>
      <p>
        <strong>Tools:</strong> {{ runner.tools.map((t) => t.name).join(", ") }}
      </p>
      <p>
        <strong>Workspace:</strong>
        {{ runner.workspaces ? runner.workspaceRoot : "Not required" }}
      </p>
      <small>{{ runner.runnerId }}</small>
    </article>
  </section>
</template>
<script setup>
import { onMounted, ref } from "vue";
import { ElAlert } from "element-plus";
import { request } from "../api/http.js";
const primaryRunnerId = ref(null);
const runners = ref([]),
  error = ref("");
async function load() {
  try {
    const [rows, defaults] = await Promise.all([
      request("/api/v1/runners"),
      request("/api/v1/personal/defaults").catch(() => null),
    ]);
    runners.value = rows;
    primaryRunnerId.value = defaults?.primaryRunnerId || null;
    error.value = "";
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(load);
</script>
<style scoped>
.runners-settings {
  height: 100%;
  overflow: auto;
  padding: 24px;
  box-sizing: border-box;
}
article {
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  padding: 20px;
  margin-top: 16px;
  overflow-wrap: anywhere;
}
header {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
}
h3 {
  margin: 0;
  flex: 1;
}
.el-tag {
  margin-right: 8px;
}
small {
  color: var(--el-text-color-secondary);
}
</style>
