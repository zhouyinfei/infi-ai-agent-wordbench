<script setup>
import { onMounted, ref } from 'vue';
import { reactApi } from '../api/client';
const config = ref(null);
const loading = ref(false);
const error = ref('');
async function load() {
  loading.value = true;
  error.value = '';
  try { config.value = await reactApi.configuration(); }
  catch (exception) { error.value = exception.message; }
  finally { loading.value = false; }
}
onMounted(load);
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">MODEL & TOOLS</span><h1>模型与工具</h1><p class="muted">查看当前模型配置和 Agent 可以自动调用的工具。</p></div><button class="secondary" :disabled="loading" @click="load">刷新配置</button></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="loading" class="muted" role="status">正在加载…</p>
  <template v-else-if="config">
    <section class="card"><h2>当前模型</h2><p>{{ config.modelName || '未配置' }} <span :class="['badge', config.configured ? 'success' : 'disabled']">{{ config.configured ? '已配置' : '尚未配置' }}</span></p><p class="hint">配置在后端配置文件中维护，修改后重启后端并刷新。</p></section>
    <section class="card"><h2>可用工具</h2><p class="hint">Agent 根据任务自动选择工具，无需手动勾选。</p><article v-for="tool in config.tools" :key="tool.name" class="tool"><strong>{{ tool.name }}</strong><p class="muted">{{ tool.description }}</p></article><p v-if="!config.tools?.length" class="muted">暂无可用工具。</p></section>
    <section class="card"><h2>执行限制</h2><p class="hint">以下为 ReAct 和 Plan-and-Execute 的执行预算。</p><dl><dt>最多模型调用</dt><dd>{{ config.maxIterations }} 次</dd><dt>最多工具调用</dt><dd>{{ config.maxToolCalls }} 次</dd><dt>执行时限</dt><dd>{{ config.taskTimeoutSeconds }} 秒</dd></dl></section>
  </template>
</template>
<style scoped>
.card { margin-bottom:20px; }
.tool { border-top:1px solid #edf0f6; padding:16px 0; overflow-wrap:anywhere; }
.tool p { margin:8px 0 0; font-size:13px; }
dl { display:grid; grid-template-columns:150px 1fr; gap:16px; font-size:14px; } dd { margin:0; } dt { color:#8590a5; }
</style>
