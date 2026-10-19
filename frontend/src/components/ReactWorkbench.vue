<script setup>
import { computed, nextTick, onMounted, ref } from 'vue';
import { conversationApi, multiAgentApi, planExecuteApi, reactApi } from '../api/client';

const thread = ref(null);
const submittedGoal = ref('');
const editingTitle = ref(false);
const titleDraft = ref('');
const savingTitle = ref(false);
const config = ref(null);
const goal = ref('');
const conversationId = ref('');
const conversations = ref([]);
const conversationHistory = ref([]);
const nextHistoryId = ref(null);
const conversationLoading = ref(false);
let selectionRevision = 0;
const mode = ref('REACT');
const modeLabels = { REACT: 'ReAct', PLAN_EXECUTE: 'Plan-and-Execute', MULTI_AGENT: 'Multi-Agent' };
const modeLabel = computed(() => modeLabels[mode.value]);
const assignments = computed(() => result.value?.steps.find(step => step.type === 'COORDINATE' && step.summary === '协作分工')?.arguments || []);
const agentLabels = { CALCULATOR: '计算 Agent', TIME: '时间 Agent', FILE: '文件 Agent', NETWORK: '联网 Agent', ANALYST: '分析 Agent' };
const plan = computed(() => result.value?.steps.find(step => step.type === 'PLAN' && step.summary === '执行计划')?.arguments || []);
const tokenCount = value => value == null ? '未提供' : value.toLocaleString();
const stepLabels = { PLAN: '生成计划', MODEL: '模型判断', TOOL: '工具执行', STEP: '步骤完成', FINAL: '汇总回答', COORDINATE: '协调分工', AGENT: '专业执行', REVIEW: '独立审校' };
const running = ref(false);
const loading = ref(true);
const error = ref('');
const result = ref(null);
const labels = { SUCCEEDED: '执行完成', FAILED: '执行失败', TIMEOUT: '执行超时', LIMIT_REACHED: '达到执行上限' };

const menuPosition = ref({});

function positionConversationMenu(event) {
  if (!event.currentTarget.open) return;
  const bounds = event.currentTarget.querySelector('summary').getBoundingClientRect();
  menuPosition.value = {
    left: Math.max(8, Math.min(bounds.right - 140, window.innerWidth - 148)) + 'px',
    top: Math.max(8, Math.min(bounds.bottom + 6, window.innerHeight - 100)) + 'px',
  };
}

function closeMenuOnBlur(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.removeAttribute('open');
}

function editTitle() {
  titleDraft.value = conversations.value.find(item => item.id === conversationId.value)?.title || '';
  editingTitle.value = true;
}

async function saveTitle() {
  if (savingTitle.value || !titleDraft.value.trim()) return;
  savingTitle.value = true;
  error.value = '';
  try {
    const updated = await conversationApi.rename(conversationId.value, titleDraft.value.trim());
    conversations.value = conversations.value.map(item => item.id === updated.id ? updated : item);
    editingTitle.value = false;
  } catch (exception) { error.value = exception.message; }
  finally { savingTitle.value = false; }
}

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
  if (!goal.value.trim() || !config.value?.configured || conversationLoading.value) return;
  submittedGoal.value = goal.value;
  running.value = true;
  error.value = '';
  result.value = null;
  try {
    const api = { REACT: reactApi, PLAN_EXECUTE: planExecuteApi, MULTI_AGENT: multiAgentApi }[mode.value];
    if (!conversationId.value) {
      const conversation = await conversationApi.create(Array.from(goal.value.trim().replace(/\s+/g, ' ')).slice(0, 24).join(''));
      conversations.value.unshift(conversation);
      conversationId.value = conversation.id;
    }
    const current = conversations.value.find(item => item.id === conversationId.value);
    if (!conversationHistory.value.length && current?.title === '新会话') {
      const renamed = await conversationApi.rename(conversationId.value,
        Array.from(goal.value.trim().replace(/\s+/g, ' ')).slice(0, 24).join(''));
      conversations.value = conversations.value.map(item => item.id === renamed.id ? renamed : item);
    }
    result.value = await api.run({ goal: goal.value, conversationId: conversationId.value });
    await loadHistory();
    goal.value = '';
    editingTitle.value = false;
  } catch (exception) { error.value = exception.message; }
  finally { running.value = false; await nextTick(); if (thread.value) thread.value.scrollTop = thread.value.scrollHeight; }
}

async function loadHistory(older = false) {
  if (!conversationId.value) { conversationHistory.value = []; nextHistoryId.value = null; return; }
  const page = await conversationApi.history(conversationId.value, older ? nextHistoryId.value : null);
  conversationHistory.value = older ? [...page.records, ...conversationHistory.value] : page.records;
  nextHistoryId.value = page.nextBeforeId;
}

async function loadConversations() {
  const revision = selectionRevision;
  conversationLoading.value = true;
  try {
    conversations.value = await conversationApi.list();
    if (revision === selectionRevision) {
      conversationId.value = conversations.value[0]?.id || '';
      await loadHistory();
    }
  } catch (exception) { error.value = exception.message; }
  finally { conversationLoading.value = false; }
}

async function switchConversation() {
  result.value = null;
  editingTitle.value = false;
  conversationLoading.value = true;
  try { await loadHistory(); }
  catch (exception) { error.value = exception.message; }
  finally { conversationLoading.value = false; }
}

async function newConversation() {
  selectionRevision++;
  conversationId.value = '';
  conversationHistory.value = [];
  nextHistoryId.value = null;
  result.value = null;
  goal.value = '';
  error.value = '';
  editingTitle.value = false;
}

async function selectConversation(id) {
  if (running.value || conversationLoading.value || savingTitle.value) return;
  conversationId.value = id;
  await switchConversation();
}

async function clearConversation(remove = false) {
  if (!conversationId.value || !window.confirm(remove ? '删除此会话及全部历史？' : '清空此会话的历史与记忆？')) return;
  conversationLoading.value = true;
  error.value = '';
  try {
    if (remove) {
      await conversationApi.delete(conversationId.value);
      conversations.value = conversations.value.filter(item => item.id !== conversationId.value);
      conversationId.value = '';
    } else { await conversationApi.clear(conversationId.value); }
    conversationHistory.value = [];
    nextHistoryId.value = null;
    result.value = null;
  } catch (exception) { error.value = exception.message; }
  finally { conversationLoading.value = false; }
}

async function loadOlderHistory() {
  conversationLoading.value = true;
  try { await loadHistory(true); }
  catch (exception) { error.value = exception.message; }
  finally { conversationLoading.value = false; }
}

defineExpose({ newConversation, busy: computed(() => running.value || conversationLoading.value || savingTitle.value) });
onMounted(() => { loadConfig(); loadConversations(); });
</script>

<template>
  <div class="workbench-root"><h1 class="sr-only">会话工作台</h1>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div v-if="config && !config.configured" class="react-config-notice" role="status">模型尚未配置，请配置支持工具调用的模型后刷新。<button class="text-button" @click="loadConfig">刷新配置</button></div>
  <div class="chat-layout">
    <Teleport defer to="#conversation-sidebar"><aside class="session-panel">
      <div class="recent-conversations"><h2>最近会话</h2><p v-if="!conversations.length" class="hint">发送消息后，会话会出现在这里。</p><div class="recent-list"><div v-for="item in conversations" :key="item.id" :class="['recent-row', { selected: conversationId === item.id }]"><button :class="['recent-item', { selected: conversationId === item.id }]" :title="item.title" :aria-pressed="conversationId === item.id" :disabled="running || conversationLoading || savingTitle" @click="selectConversation(item.id)">{{ item.title }}</button><details v-if="conversationId === item.id" class="conversation-menu" @toggle="positionConversationMenu" @keydown.esc="$event.currentTarget.removeAttribute('open')" @focusout="closeMenuOnBlur"><summary aria-label="会话操作" :aria-disabled="running || conversationLoading || savingTitle" @click="(running || conversationLoading || savingTitle) && $event.preventDefault()">⋯</summary><div class="conversation-menu-items" :style="menuPosition"><button type="button" :disabled="running || conversationLoading || savingTitle" @click="$event.currentTarget.closest('details').removeAttribute('open'); editTitle()">修改标题</button><button type="button" class="delete-action" :disabled="running || conversationLoading || savingTitle" @click="$event.currentTarget.closest('details').removeAttribute('open'); clearConversation(true)">删除会话</button></div></details></div></div></div>
      <select v-model="conversationId" aria-label="当前会话" :disabled="running || conversationLoading" @change="switchConversation"><option value="">新会话（发送时创建）</option><option v-for="item in conversations" :key="item.id" :value="item.id">{{ item.title }}</option></select>

      <form v-if="editingTitle" class="title-editor" @submit.prevent="saveTitle"><label>会话标题<input v-model="titleDraft" aria-label="会话标题" maxlength="80" required :disabled="savingTitle"></label><div class="session-actions"><button class="text-button" :disabled="savingTitle || !titleDraft.trim()">保存标题</button><button type="button" class="text-button" :disabled="savingTitle" @click="editingTitle = false">取消</button></div></form>
      <div class="session-actions"><button class="text-button" :disabled="running || conversationLoading || !conversationId" @click="clearConversation()">清空会话</button></div>
    </aside></Teleport>
    <section class="chat-panel">
      <div class="chat-header"><strong>{{ conversations.find(item => item.id === conversationId)?.title || '新的对话' }}</strong><div class="workbench-actions"><label class="mode-selector">处理方式<select aria-label="执行模式" v-model="mode" :disabled="running || loading" @change="result = null; loadConfig()"><option value="REACT">直接处理（ReAct）</option><option value="PLAN_EXECUTE">先做计划（Plan-and-Execute）</option><option value="MULTI_AGENT">团队协作（Multi-Agent）</option></select></label></div></div>
      <div ref="thread" class="chat-thread" aria-label="对话消息" :aria-busy="running || conversationLoading">
        <p v-if="conversationLoading" class="muted" role="status">正在加载会话…</p>
        <div v-else-if="!conversationHistory.length && !running" class="welcome"><span class="welcome-icon">✦</span><h2>今天想完成什么？</h2><p class="muted">直接输入问题，或从下面的示例开始。</p><div class="react-examples"><button class="secondary" @click="goal = '先计算128加256，再将结果乘以3，请使用工具并告诉我结果。'">多步计算</button><button class="secondary" @click="goal = '查询上海和UTC的当前时间，说明时区差异。'">当前时间</button></div></div>
        <div v-if="conversationHistory.length" class="conversation-history"><h2 class="history-label">会话历史</h2><button v-if="nextHistoryId" class="text-button" :disabled="running || conversationLoading" @click="loadOlderHistory">加载更早消息</button>
          <article v-for="turn in conversationHistory" :key="turn.id" class="conversation-turn">
            <div class="message user-message"><small>你</small><p>{{ turn.goal }}</p></div>
            <div class="message assistant-message"><small>Agent · {{ modeLabels[turn.mode] || turn.mode }}</small><div v-if="turn.answer" class="react-answer">{{ turn.answer }}</div><p v-else class="muted">本轮未完成，未加入模型记忆。你可以调整任务后重试。</p></div>
          </article>
        </div>
        <div v-if="running" class="pending-turn" role="status"><div class="message user-message"><small>你</small><p>{{ submittedGoal }}</p></div><div class="message assistant-message"><span class="status-dot"></span> 正在处理，请稍候…</div></div>
        <div v-if="result" class="execution-footer"><h2>{{ labels[result.status] || result.status }}</h2><p v-if="result.stopReason" class="error">{{ result.stopReason }}</p><div v-if="result.answer && !conversationHistory.length" class="react-answer">{{ result.answer }}</div><p class="run-usage" data-testid="run-token-summary">本次执行：{{ tokenCount(result.tokenUsage?.totalTokens) }} Token · {{ result.toolCalls }} 次工具调用 · {{ result.durationMs }}ms<span v-if="result.tokenUsage?.reportedModelCalls && !result.tokenUsage.complete">（Token 为部分统计）</span></p><details class="execution-details"><summary>查看执行详情</summary>
        <template v-if="result">
          <section v-if="assignments.length" class="detail-section"><h2>协作分工</h2><ol class="plan-list collaboration-list"><li v-for="(assignment, index) in assignments" :key="index"><strong>{{ agentLabels[assignment.agent] || assignment.agent }}</strong>：{{ assignment.goal }} <span class="badge" :class="{ success: result.steps.some(step => step.type === 'AGENT' && step.iteration === index + 1) }">{{ result.steps.some(step => step.type === 'AGENT' && step.iteration === index + 1) ? '已完成' : '未完成' }}</span></li></ol></section>
          <section v-if="plan.length" class="detail-section"><h2>执行计划</h2><ol class="plan-list"><li v-for="(item, index) in plan" :key="index">{{ item }} <span class="badge" :class="{ success: result.steps.some(step => step.type === 'STEP' && step.iteration === index + 1) }">{{ result.steps.some(step => step.type === 'STEP' && step.iteration === index + 1) ? '已完成' : '未完成' }}</span></li></ol></section>
          
          <section class="detail-section"><h2>执行过程</h2><p class="muted">展示工具动作和实际观察结果。</p>
            <div v-for="step in result.steps" :key="step.sequence" class="react-step">
              <div class="react-step-title"><span :class="['badge', step.status === 'SUCCESS' ? 'success' : 'disabled']">{{ stepLabels[step.type] || step.type }}</span><span v-if="step.agentName" class="badge agent-role">{{ step.agentName }}</span><strong>{{ step.toolName || step.summary }}</strong><small class="muted">{{ mode === 'REACT' ? '第' + step.iteration + '轮' : (step.type === 'REVIEW' ? '审校阶段' : step.type === 'FINAL' ? '汇总阶段' : (step.iteration ? '步骤' + step.iteration : '规划阶段')) }} · {{ step.durationMs }}ms</small></div>
              <pre v-if="step.arguments">{{ JSON.stringify(step.arguments, null, 2) }}</pre>
              <div v-if="step.observation" class="react-observation" :class="{ 'tool-error': step.status === 'ERROR' }">{{ step.observation }}</div>
            </div>
          </section>
        </template>

<div class="detail-section"><h2>本次执行</h2><dl><dt>状态</dt><dd>{{ labels[result.status] }}</dd><dt>模型调用</dt><dd>{{ result.modelCalls }} 次</dd><dt>工具调用</dt><dd>{{ result.toolCalls }} 次</dd><dt>总耗时</dt><dd>{{ result.durationMs }}ms</dd><dt>输入 Token</dt><dd>{{ tokenCount(result.tokenUsage?.inputTokens) }}</dd><dt>输出 Token</dt><dd>{{ tokenCount(result.tokenUsage?.outputTokens) }}</dd><dt>总 Token</dt><dd>{{ tokenCount(result.tokenUsage?.totalTokens) }}</dd></dl><p class="hint" data-testid="token-usage-status">{{ !result.modelCalls ? '未调用模型' : !result.tokenUsage?.reportedModelCalls ? '模型服务未返回 Token 用量' : result.tokenUsage.complete ? 'Token 用量统计完整' : 'Token 用量为部分统计，可能低于实际消耗' }}<template v-if="result.modelCalls && result.tokenUsage?.reportedModelCalls"> · 已报告 {{ result.tokenUsage.reportedModelCalls }}/{{ result.modelCalls }} 次调用</template></p><p class="hint">执行编号：{{ result.runId }}</p>
      </div></details></div>
      </div>
      <form class="composer" @submit.prevent="execute">
        <textarea v-model="goal" required maxlength="2000" rows="1" :disabled="running" aria-label="任务目标" placeholder="输入你的问题或任务，也可以接着上一次回答追问…"></textarea>
        <button class="primary send-button" type="submit" :disabled="running || loading || conversationLoading || !config?.configured || !goal.trim()"><span aria-hidden="true">➤</span>{{ running ? '执行中…' : '发送' }}</button>
      </form>
    </section>
  </div></div>
</template>
<style scoped>
.chat-layout { display:grid; grid-template-columns:220px minmax(0,1fr); gap:20px; align-items:start; }
.title-editor { width:100%; } .title-editor label { margin:12px 0; } .title-editor input { width:100%; min-width:0; padding:9px; border:1px solid #dce2ed; border-radius:8px; font:inherit; }
.session-panel { padding:20px; min-width:0; } .session-panel > .primary { width:100%; } .session-panel h2 { font-size:16px; margin:24px 0 12px; }
select { width:100%; padding:10px; border:1px solid #dce2ed; border-radius:8px; font:inherit; background:white; min-width:0; }
.session-actions { display:flex; gap:20px; margin:20px 0; font-size:13px; }
.model-settings { border-top:1px solid #edf0f6; padding-top:16px; font-size:13px; } summary { cursor:pointer; color:#3567f6; line-height:1.7; }
.chat-panel { padding:0; overflow:hidden; min-width:0; }
.chat-header { display:flex; justify-content:space-between; align-items:center; padding:18px 24px; border-bottom:1px solid #edf0f6; gap:12px; } .chat-header strong { overflow-wrap:anywhere; }
.chat-thread { height:480px; overflow-y:auto; padding:24px; overscroll-behavior:contain; }
.welcome { text-align:center; padding:75px 0; } .welcome-icon { font-size:36px; color:#3567f6; } .welcome h2 { margin:18px 0 12px; } .react-examples { display:flex; justify-content:center; gap:12px; margin-top:24px; }
.history-label { font-size:12px; color:#8590a5; font-weight:400; margin:0 0 20px; }
.conversation-turn { margin-bottom:24px; } .message { max-width:92%; margin:0 0 16px; padding:14px 18px; border-radius:12px; overflow-wrap:anywhere; }
.message small { display:block; color:#8590a5; margin-bottom:8px; font-size:12px; } .message p { margin:0; white-space:pre-wrap; line-height:1.8; }
.user-message { margin-left:auto; background:#edf2ff; } .assistant-message { background:#f6f8fb; }
.composer { padding:18px 24px; border-top:1px solid #edf0f6; background:#fff; } .mode-selector { display:flex; flex-direction:row; align-items:center; gap:12px; margin:0; font-size:13px; } .mode-selector select { width:auto; max-width:80%; }
.mode-help { font-size:12px; color:#8590a5; margin:10px 0 14px; line-height:1.6; }
textarea { width:100%; padding:14px; font:inherit; color:#19253d; border:1px solid #dce2ed; border-radius:10px; resize:vertical; line-height:1.7; } textarea:focus-visible { outline:3px solid #b1c5ff; }
.composer-footer { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-top:12px; } .tool-hint { font-size:12px; margin:0; line-height:1.6; } .composer-footer button { flex-shrink:0; }
.react-config-notice { background:#fff6df; padding:15px; border-radius:8px; margin-bottom:20px; color:#94742d; font-size:14px; } .react-config-notice button { margin-left:15px; }
.execution-footer { padding:0 4px 16px; } .execution-footer > h2 { font-size:13px; color:#8590a5; } .execution-details { border:1px solid #e4e9f2; border-radius:10px; padding:12px 16px; }
.detail-section { padding:14px 0; } .detail-section h2 { font-size:15px; } .plan-list { padding-left:22px; line-height:2; overflow-wrap:anywhere; } .plan-list .badge { margin-left:8px; }
.react-answer,.react-observation { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.8; font-size:14px; }
.react-step { padding:16px 0; border-top:1px solid #edf0f6; } .react-step-title { display:flex; align-items:center; flex-wrap:wrap; gap:10px; font-size:13px; } .react-step-title small { color:#8590a5; }
pre { background:#f7f9fd; padding:12px; border-radius:8px; white-space:pre-wrap; overflow-wrap:anywhere; font-size:12px; }
.react-observation { background:#effaf4; padding:12px; border-radius:8px; margin-top:12px; } .tool-error { background:#fff0f0; color:#b42e35; }
dl { display:grid; grid-template-columns:1fr 1fr; gap:16px 10px; font-size:13px; } dt { color:#8590a5; } dd { margin:0; text-align:right; overflow-wrap:anywhere; }
@media(max-width:1000px) { .chat-layout { grid-template-columns:1fr; } .session-panel { display:flex; flex-wrap:wrap; gap:12px; align-items:center; } .session-panel > .primary { width:auto; } .session-panel h2,.session-panel .hint { display:none; } .session-panel > select { flex:1; width:auto; min-width:160px; } .session-actions { margin:0; } .model-settings { width:100%; } }
@media(max-width:600px) { .chat-thread { height:400px; padding:16px; } .composer,.chat-header { padding:16px; } .composer-footer { align-items:flex-end; } .message { max-width:100%; } .welcome { padding:45px 0; } .mode-selector { flex-direction:column; align-items:flex-start; } .mode-selector select { width:100%; max-width:100%; } }

/* 会话区与应用左侧导航共用侧栏，正文和输入框独立滚动。 */
.workbench-root { height:100%; min-height:0; display:flex; flex-direction:column; background:#fff; }
.sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
.chat-layout { display:flex; flex:1; min-height:0; align-items:stretch; }
.session-panel { padding:0; display:flex; flex-direction:column; flex-wrap:nowrap; align-items:stretch; gap:12px; height:100%; min-height:0; }
.session-panel > :not(.recent-conversations) { flex-shrink:0; }
.session-panel > .primary { background:#eceef1; color:#303641; text-align:left; font-weight:500; width:100%; }
.session-panel > select { display:none; }
.recent-conversations { margin-top:auto; min-height:72px; display:flex; flex-direction:column; flex:0 1 auto; }
.recent-conversations h2, .recent-conversations > .hint { flex-shrink:0; }
.session-panel .recent-conversations h2 { display:block; font-size:12px; color:#8a8f99; font-weight:500; margin:12px 8px; }
.recent-list { max-height:260px; min-height:0; flex:1; overflow-y:auto; }
.recent-item { display:block; width:100%; text-align:left; background:transparent; padding:10px 12px; color:#434954; font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.recent-item:hover,.recent-item.selected { background:#e8eaee; }
.session-actions { margin:0; gap:18px; font-size:12px; }
.session-panel > .text-button { text-align:left; font-size:12px; }
.model-settings { padding-top:10px; } .title-editor label { margin:0 0 8px; }
.chat-panel { display:flex; flex-direction:column; flex:1; min-height:0; width:100%; border:0; border-radius:0; box-shadow:none; }
.chat-header { border-bottom:0; padding:16px 32px; font-size:13px; color:#6c7483; }
.chat-header strong { max-width:75%; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-weight:500; }
.chat-thread { height:auto; flex:1; min-height:0; padding:24px max(28px, calc((100% - 840px)/2)); }
.welcome { height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; padding:24px 0; } .welcome h2 { font-size:30px; font-weight:500; } .welcome-icon { color:#999; }
.message { max-width:90%; } .assistant-message { background:transparent; padding-left:0; } .user-message { background:#f1f2f4; max-width:80%; }
.history-label { display:none; }
.composer { flex-shrink:0; width:calc(100% - 56px); max-width:840px; margin:0 auto 24px; padding:16px 20px; border:1px solid #e4e5e8; border-radius:22px; box-shadow:0 4px 20px #00000007; }
.composer textarea { border:0; padding:10px 0; resize:none; background:transparent; }
.mode-selector select { border:0; background:#f4f5f6; font-size:12px; }
.mode-help { margin:6px 0 4px; } .composer-footer { margin-top:6px; }
@media(max-width:700px) { .session-panel { display:block; } .session-panel > .primary { margin-bottom:8px; } .recent-list { max-height:160px; } .session-actions { margin:10px 0; } .model-settings { margin-top:10px; } .chat-header { padding:14px 16px; } .chat-thread { padding:16px; min-height:160px; } .composer { width:calc(100% - 24px); margin-bottom:12px; padding:12px; } .welcome h2 { font-size:24px; } }


.workbench-actions { display:flex; align-items:center; gap:12px; flex-shrink:0; }
.workbench-actions .secondary { font-size:12px; padding:8px 12px; }
.chat-header strong { min-width:0; flex:1; }
.workbench-settings { padding:0 32px 10px; flex-shrink:0; }
.workbench-settings .model-settings { border:0; margin:0; padding:0; width:100%; font-size:12px; }
.workbench-settings dl { max-width:560px; background:#f7f8fa; padding:16px; border-radius:10px; margin:10px 0 0; }
.run-usage { font-size:12px; color:#8590a5; line-height:1.7; margin:8px 0 12px; }
@media(max-width:700px) { .workbench-settings { padding:0 16px 8px; } .workbench-actions { gap:4px; } .workbench-actions .badge { display:none; } }

.composer-footer { justify-content:flex-end; }
.chat-header .mode-selector { flex-direction:row; align-items:center; gap:8px; white-space:nowrap; }
.chat-header .mode-selector select { width:auto; max-width:100%; }
@media(max-width:600px) {
  .chat-header { flex-wrap:wrap; }
  .chat-header .workbench-actions { margin-left:auto; max-width:100%; }
  .chat-header .mode-selector { font-size:12px; max-width:100%; }
  .chat-header .mode-selector select { min-width:0; max-width:240px; }
}

.composer { position:relative; padding:12px 16px; border-radius:14px; }
.composer textarea { display:block; height:72px; min-height:72px; padding:4px 0 40px; font-size:14px; line-height:1.7; }
.send-button { position:absolute; right:12px; bottom:12px; display:flex; align-items:center; gap:7px; padding:8px 14px; font-size:13px; line-height:18px; border-radius:7px; }
.send-button span { font-size:14px; }
@media(max-width:600px) { .composer { padding:10px 12px; } .composer textarea { height:72px; min-height:72px; } .send-button { right:10px; bottom:10px; } }
.composer { padding-top:8px; padding-bottom:8px; margin-bottom:12px; }
.chat-header { padding-top:10px; padding-bottom:10px; }

.recent-row { display:flex; flex-wrap:wrap; align-items:center; border-radius:8px; }
.recent-row.selected { background:#e8eaee; }
.recent-row .recent-item { flex:1; min-width:0; width:auto; }
.conversation-menu { display:contents; }
.conversation-menu summary { list-style:none; color:#626f87; font-size:22px; line-height:28px; width:28px; text-align:center; margin-right:5px; border-radius:6px; cursor:pointer; }
.conversation-menu summary::-webkit-details-marker { display:none; }
.conversation-menu summary:hover { background:#dce0e7; }
.conversation-menu summary:focus-visible { outline:2px solid #3567f6; }
.conversation-menu-items { width:100%; padding:4px; background:white; border:1px solid #e4e9f2; border-radius:8px; margin:0 5px 6px; }
.conversation-menu-items button { display:block; width:100%; padding:8px 10px; text-align:left; background:transparent; color:#37445e; font-size:12px; }
.conversation-menu-items button:hover { background:#f4f6fa; }
.conversation-menu-items .delete-action { color:#b42e35; }
.conversation-menu-items { position:fixed; z-index:100; width:140px; margin:0; box-shadow:0 6px 24px #19253d20; }
.recent-row { flex-wrap:nowrap; }
</style>
