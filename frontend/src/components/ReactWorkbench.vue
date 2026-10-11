<script setup>
import { computed, onMounted, ref } from 'vue';
import { multiAgentApi, planExecuteApi, reactApi } from '../api/client';

const config = ref(null);
const goal = ref('');
const mode = ref('REACT');
const modeLabels = { REACT: 'ReAct', PLAN_EXECUTE: 'Plan-and-Execute', MULTI_AGENT: 'Multi-Agent' };
const modeLabel = computed(() => modeLabels[mode.value]);
const assignments = computed(() => result.value?.steps.find(step => step.type === 'COORDINATE' && step.summary === '协作分工')?.arguments || []);
const agentLabels = { CALCULATOR: '计算 Agent', TIME: '时间 Agent', ANALYST: '分析 Agent' };
const plan = computed(() => result.value?.steps.find(step => step.type === 'PLAN' && step.summary === '执行计划')?.arguments || []);
const tokenCount = value => value == null ? '未提供' : value.toLocaleString();
const stepLabels = { PLAN: '生成计划', MODEL: '模型判断', TOOL: '工具执行', STEP: '步骤完成', FINAL: '汇总回答', COORDINATE: '协调分工', AGENT: '专业执行', REVIEW: '独立审校' };
const running = ref(false);
const loading = ref(true);
const error = ref('');
const result = ref(null);
const labels = { SUCCEEDED: '执行完成', FAILED: '执行失败', TIMEOUT: '执行超时', LIMIT_REACHED: '达到执行上限' };

async function loadConfig() {
  loading.value = true;
  error.value = '';
  try {
    config.value = await (mode.value === 'MULTI_AGENT' ? multiAgentApi : reactApi).configuration();
  } catch (exception) { error.value = exception.message; }
  finally { loading.value = false; }
}

async function execute() {
  if (running.value) return;
  running.value = true;
  error.value = '';
  result.value = null;
  try {
    const api = { REACT: reactApi, PLAN_EXECUTE: planExecuteApi, MULTI_AGENT: multiAgentApi }[mode.value];
    result.value = await api.run({ goal: goal.value });
  } catch (exception) { error.value = exception.message; }
  finally { running.value = false; }
}

onMounted(loadConfig);
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">AI AGENT</span><h1>会话工作台</h1><p class="muted">提交一个目标，让 Agent 使用工具逐步完成。</p></div><span class="badge">{{ modeLabel }}</span></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="loading" class="muted">正在读取执行配置…</p>
  <template v-else-if="config">
    <div v-if="!config.configured" class="react-config-notice" role="status">模型尚未配置，请配置支持工具调用的模型后刷新。<button class="text-button" @click="loadConfig">刷新配置</button></div>
    <div class="react-grid">
      <section class="react-main">
        <section class="card">
          <h2>任务目标</h2><p class="muted">每次执行独立处理，当前不保留跨任务对话。</p>
          <form @submit.prevent="execute">
            <label class="mode-selector">执行模式<select v-model="mode" :disabled="running || loading" @change="result = null; loadConfig()"><option value="REACT">ReAct</option><option value="PLAN_EXECUTE">Plan-and-Execute</option><option value="MULTI_AGENT">Multi-Agent</option></select></label>
            <p v-if="mode === 'MULTI_AGENT'" class="muted">协调 Agent 分工，专业 Agent 顺序执行，审校 Agent 独立检查结果。</p>
            <p v-if="mode === 'PLAN_EXECUTE'" class="muted">先生成计划，再逐步执行并汇总；所有阶段共用执行预算。</p>
            <textarea v-model="goal" required maxlength="2000" rows="5" :disabled="running" aria-label="任务目标" placeholder="例如：先计算128加256，再将结果乘以3，请使用工具并告诉我结果。"></textarea>
            <div class="react-examples"><button type="button" class="text-button" :disabled="running" @click="goal = '先计算128加256，再将结果乘以3，请使用工具并告诉我结果。'">多步计算</button><button type="button" class="text-button" :disabled="running" @click="goal = '查询上海和UTC的当前时间，说明时区差异。'">当前时间</button></div>
            <p class="tool-hint muted">Agent 会根据任务自动选择并调用可用工具，无需手动选择。</p>
            <button class="primary" :disabled="running || !config.configured || !goal.trim()">{{ running ? '正在执行…' : '开始执行' }}</button>
          </form>
        </section>
        <section v-if="running" class="card running-card" role="status"><span class="status-dot"></span>Agent 正在判断下一步并执行工具，完成后将展示完整过程。</section>
        <template v-if="result">
          <section v-if="assignments.length" class="card"><h2>协作分工</h2><ol class="plan-list collaboration-list"><li v-for="(assignment, index) in assignments" :key="index"><strong>{{ agentLabels[assignment.agent] || assignment.agent }}</strong>：{{ assignment.goal }} <span class="badge" :class="{ success: result.steps.some(step => step.type === 'AGENT' && step.iteration === index + 1) }">{{ result.steps.some(step => step.type === 'AGENT' && step.iteration === index + 1) ? '已完成' : '未完成' }}</span></li></ol></section>
          <section v-if="plan.length" class="card"><h2>执行计划</h2><ol class="plan-list"><li v-for="(item, index) in plan" :key="index">{{ item }} <span class="badge" :class="{ success: result.steps.some(step => step.type === 'STEP' && step.iteration === index + 1) }">{{ result.steps.some(step => step.type === 'STEP' && step.iteration === index + 1) ? '已完成' : '未完成' }}</span></li></ol></section>
          <section class="card"><h2>{{ labels[result.status] || result.status }}</h2><p v-if="result.stopReason" class="error">{{ result.stopReason }}</p><div v-if="result.answer" class="react-answer">{{ result.answer }}</div></section>
          <section class="card"><h2>执行过程</h2><p class="muted">展示工具动作和实际观察结果。</p>
            <div v-for="step in result.steps" :key="step.sequence" class="react-step">
              <div class="react-step-title"><span :class="['badge', step.status === 'SUCCESS' ? 'success' : 'disabled']">{{ stepLabels[step.type] || step.type }}</span><span v-if="step.agentName" class="badge agent-role">{{ step.agentName }}</span><strong>{{ step.toolName || step.summary }}</strong><small class="muted">{{ mode === 'REACT' ? '第' + step.iteration + '轮' : (step.type === 'REVIEW' ? '审校阶段' : step.type === 'FINAL' ? '汇总阶段' : (step.iteration ? '步骤' + step.iteration : '规划阶段')) }} · {{ step.durationMs }}ms</small></div>
              <pre v-if="step.arguments">{{ JSON.stringify(step.arguments, null, 2) }}</pre>
              <div v-if="step.observation" class="react-observation" :class="{ 'tool-error': step.status === 'ERROR' }">{{ step.observation }}</div>
            </div>
          </section>
        </template>
      </section>
      <aside class="card react-details"><h2>执行设置</h2><dl><dt>执行策略</dt><dd>{{ modeLabel }}</dd><dt>当前模型</dt><dd>{{ config.modelName || '未配置' }}</dd><dt>最多模型调用</dt><dd>{{ config.maxIterations }} 次</dd><dt>最多工具调用</dt><dd>{{ config.maxToolCalls }} 次</dd><dt>执行时限</dt><dd>{{ config.taskTimeoutSeconds }} 秒</dd></dl>
        <template v-if="result"><hr><h2>本次执行</h2><dl><dt>状态</dt><dd>{{ labels[result.status] }}</dd><dt>模型调用</dt><dd>{{ result.modelCalls }} 次</dd><dt>工具调用</dt><dd>{{ result.toolCalls }} 次</dd><dt>总耗时</dt><dd>{{ result.durationMs }}ms</dd><dt>输入 Token</dt><dd>{{ tokenCount(result.tokenUsage?.inputTokens) }}</dd><dt>输出 Token</dt><dd>{{ tokenCount(result.tokenUsage?.outputTokens) }}</dd><dt>总 Token</dt><dd>{{ tokenCount(result.tokenUsage?.totalTokens) }}</dd></dl><p class="hint" data-testid="token-usage-status">{{ !result.modelCalls ? '未调用模型' : !result.tokenUsage?.reportedModelCalls ? '模型服务未返回 Token 用量' : result.tokenUsage.complete ? 'Token 用量统计完整' : 'Token 用量为部分统计，可能低于实际消耗' }}<template v-if="result.modelCalls && result.tokenUsage?.reportedModelCalls"> · 已报告 {{ result.tokenUsage.reportedModelCalls }}/{{ result.modelCalls }} 次调用</template></p><p class="hint">执行编号：{{ result.runId }}</p></template>
      </aside>
    </div>
  </template>
  <button v-else-if="!loading" class="secondary" @click="loadConfig">重新加载</button>
</template>

<style scoped>
.mode-selector { display: flex; flex-direction: row; gap: 16px; align-items: center; margin-top: 20px; }
.mode-selector select { padding: 8px; border: 1px solid #dce2ed; border-radius: 8px; font: inherit; max-width: 100%; }
.plan-list { padding-left: 22px; line-height: 2; overflow-wrap: anywhere; }
.plan-list .badge { margin-left: 8px; }
.react-grid { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 24px; align-items: start; }
.react-main { display: grid; gap: 22px; min-width: 0; }
textarea { width: 100%; padding: 14px; font: inherit; color: #19253d; border: 1px solid #dce2ed; border-radius: 8px; resize: vertical; line-height: 1.7; margin-top: 14px; }
textarea:focus-visible { outline: 3px solid #b1c5ff; }
.tool-hint { margin: 20px 0; font-size: 13px; }
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
