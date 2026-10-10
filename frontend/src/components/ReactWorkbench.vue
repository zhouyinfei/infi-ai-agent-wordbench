<script setup>
import { onMounted, ref } from 'vue';
import { reactApi } from '../api/client';

const config = ref(null);
const goal = ref('');
const allowedTools = ref([]);
const running = ref(false);
const loading = ref(true);
const error = ref('');
const result = ref(null);
const labels = { SUCCEEDED: '执行完成', FAILED: '执行失败', TIMEOUT: '执行超时', LIMIT_REACHED: '达到执行上限' };

async function loadConfig() {
  loading.value = true;
  error.value = '';
  try {
    config.value = await reactApi.configuration();
    allowedTools.value = config.value.tools.map(tool => tool.name);
  } catch (exception) { error.value = exception.message; }
  finally { loading.value = false; }
}

async function execute() {
  if (running.value) return;
  running.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await reactApi.run({ goal: goal.value, allowedTools: allowedTools.value });
  } catch (exception) { error.value = exception.message; }
  finally { running.value = false; }
}

onMounted(loadConfig);
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">REACT AGENT</span><h1>会话工作台</h1><p class="muted">提交一个目标，让 Agent 使用工具逐步完成。</p></div><span class="badge">ReAct</span></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="loading" class="muted">正在读取执行配置…</p>
  <template v-else-if="config">
    <div v-if="!config.configured" class="react-config-notice" role="status">模型尚未配置，请配置支持工具调用的模型后刷新。<button class="text-button" @click="loadConfig">刷新配置</button></div>
    <div class="react-grid">
      <section class="react-main">
        <section class="card">
          <h2>任务目标</h2><p class="muted">每次执行独立处理，当前不保留跨任务对话。</p>
          <form @submit.prevent="execute">
            <textarea v-model="goal" required maxlength="2000" rows="5" :disabled="running" aria-label="任务目标" placeholder="例如：先计算128加256，再将结果乘以3，请使用工具并告诉我结果。"></textarea>
            <div class="react-examples"><button type="button" class="text-button" :disabled="running" @click="goal = '先计算128加256，再将结果乘以3，请使用工具并告诉我结果。'">多步计算</button><button type="button" class="text-button" :disabled="running" @click="goal = '查询上海和UTC的当前时间，说明时区差异。'">当前时间</button></div>
            <fieldset :disabled="running"><legend>允许使用的工具</legend><label v-for="tool in config.tools" :key="tool.name" class="tool-option"><input v-model="allowedTools" type="checkbox" :value="tool.name"><span>{{ tool.name === 'calculator' ? '计算器' : '当前时间' }}</span></label></fieldset>
            <button class="primary" :disabled="running || !config.configured || !goal.trim() || !allowedTools.length">{{ running ? '正在执行…' : '开始执行' }}</button>
          </form>
        </section>
        <section v-if="running" class="card running-card" role="status"><span class="status-dot"></span>Agent 正在判断下一步并执行工具，完成后将展示完整过程。</section>
        <template v-if="result">
          <section class="card"><h2>{{ labels[result.status] || result.status }}</h2><p v-if="result.stopReason" class="error">{{ result.stopReason }}</p><div v-if="result.answer" class="react-answer">{{ result.answer }}</div></section>
          <section class="card"><h2>执行过程</h2><p class="muted">展示工具动作和实际观察结果。</p>
            <div v-for="step in result.steps" :key="step.sequence" class="react-step">
              <div class="react-step-title"><span :class="['badge', step.status === 'SUCCESS' ? 'success' : 'disabled']">{{ step.type === 'MODEL' ? '模型判断' : '工具执行' }}</span><strong>{{ step.toolName || step.summary }}</strong><small class="muted">第{{ step.iteration }}轮 · {{ step.durationMs }}ms</small></div>
              <pre v-if="step.arguments">{{ JSON.stringify(step.arguments, null, 2) }}</pre>
              <div v-if="step.observation" class="react-observation" :class="{ 'tool-error': step.status === 'ERROR' }">{{ step.observation }}</div>
            </div>
          </section>
        </template>
      </section>
      <aside class="card react-details"><h2>执行设置</h2><dl><dt>执行策略</dt><dd>ReAct</dd><dt>当前模型</dt><dd>{{ config.modelName || '未配置' }}</dd><dt>最多模型调用</dt><dd>{{ config.maxIterations }} 次</dd><dt>最多工具调用</dt><dd>{{ config.maxToolCalls }} 次</dd><dt>执行时限</dt><dd>{{ config.taskTimeoutSeconds }} 秒</dd></dl>
        <template v-if="result"><hr><h2>本次执行</h2><dl><dt>状态</dt><dd>{{ labels[result.status] }}</dd><dt>模型调用</dt><dd>{{ result.modelCalls }} 次</dd><dt>工具调用</dt><dd>{{ result.toolCalls }} 次</dd><dt>总耗时</dt><dd>{{ result.durationMs }}ms</dd></dl><p class="hint">执行编号：{{ result.runId }}</p></template>
      </aside>
    </div>
  </template>
  <button v-else-if="!loading" class="secondary" @click="loadConfig">重新加载</button>
</template>

<style scoped>
.react-grid { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 24px; align-items: start; }
.react-main { display: grid; gap: 22px; min-width: 0; }
textarea { width: 100%; padding: 14px; font: inherit; color: #19253d; border: 1px solid #dce2ed; border-radius: 8px; resize: vertical; line-height: 1.7; margin-top: 14px; }
textarea:focus-visible { outline: 3px solid #b1c5ff; }
fieldset { border: 0; padding: 0; margin: 20px 0; }
legend { color: #8590a5; font-size: 13px; }
.tool-option { display: inline-flex; flex-direction: row; align-items: center; margin: 14px 24px 0 0; }
.tool-option input { width: auto; }
.react-examples { display: flex; gap: 20px; margin-top: 10px; font-size: 12px; }
.react-config-notice { background: #fff6df; padding: 15px; border-radius: 8px; margin-bottom: 20px; color: #94742d; font-size: 14px; }
.react-config-notice button { margin-left: 15px; }
.react-answer, .react-observation { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-size: 14px; }
.react-step { padding: 20px 0; border-top: 1px solid #edf0f6; }
.react-step-title { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; font-size: 13px; }
.react-step-title small { margin-left: auto; }
pre { background: #f7f9fd; padding: 14px; border-radius: 8px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
.react-observation { background: #effaf4; padding: 12px; border-radius: 8px; margin-top: 12px; }
.tool-error { background: #fff0f0; color: #b42e35; }
dl { display: grid; grid-template-columns: 1fr 1fr; gap: 20px 10px; font-size: 13px; margin: 25px 0 5px; }
dt { color: #8590a5; } dd { margin: 0; text-align: right; overflow-wrap: anywhere; }
hr { border: 0; border-top: 1px solid #edf0f6; margin: 28px 0; }
.react-details .hint { overflow-wrap: anywhere; margin-top: 25px; }
.running-card { color: #3567f6; font-size: 14px; }
@media (max-width: 1100px) { .react-grid { grid-template-columns: 1fr; } .react-details { order: -1; } }
</style>
