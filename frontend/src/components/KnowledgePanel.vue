<script setup>
import { computed, onMounted, ref } from 'vue';
import { knowledgeApi } from '../api/client';

const documents = ref([]);
const hits = ref([]);
const query = ref('');
const keyword = ref('');
const error = ref('');
const notice = ref('');
const busy = ref(false);
const searched = ref(false);
const pendingDelete = ref(null);
const fileInput = ref(null);
const visibleDocuments = computed(() => documents.value.filter(item => item.filename.toLowerCase().includes(keyword.value.toLowerCase())));
const readyCount = computed(() => documents.value.filter(item => item.status === 'READY').length);
const statuses = { READY: '可检索', INDEXING: '索引中', FAILED: '索引失败', DELETING: '待完成删除' };

async function action(callback) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try { await callback(); }
  catch (exception) { error.value = exception.message; }
  finally { busy.value = false; }
}
async function refresh() { await action(async () => { documents.value = await knowledgeApi.list(); }); }
async function upload(event) {
  const file = event.target.files?.[0];
  event.target.value = '';
  if (!file) return;
  if (file.size > 10 * 1024 * 1024 || !/\.(txt|md|pdf|doc|docx|xls|xlsx|ppt|pptx|html|htm|rtf|odt|ods|odp)$/i.test(file.name)) {
    error.value = '请选择 10 MB 以内的 TXT、Markdown、PDF、Word、Excel、PowerPoint、HTML、RTF 或 OpenDocument 文件。';
    return;
  }
  await action(async () => {
    try {
      await knowledgeApi.upload(file);
      notice.value = '文档已入库，可以在会话中要求 Agent 检索知识库。';
    } finally {
      documents.value = await knowledgeApi.list();
    }
  });
}
async function remove() {
  const id = pendingDelete.value.id;
  await action(async () => {
    await knowledgeApi.delete(id);
    documents.value = documents.value.filter(item => item.id !== id);
    hits.value = hits.value.filter(item => item.documentId !== id);
    pendingDelete.value = null;
    notice.value = '文档原文及向量已删除。';
  });
}
async function search() {
  if (!query.value.trim()) return;
  await action(async () => {
    searched.value = false;
    hits.value = [];
    hits.value = await knowledgeApi.search(query.value.trim());
    searched.value = true;
  });
}
onMounted(refresh);
</script>

<template>
  <section class="knowledge-page">
    <header class="knowledge-heading"><div><h1>知识库 <span class="knowledge-tag">RAG</span></h1><p class="muted">让每一次回答，都有资料可循。</p></div><button class="primary" :disabled="busy" @click="fileInput.click()">＋ 上传文档</button><input ref="fileInput" class="knowledge-file" type="file" accept=".txt,.md,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.html,.htm,.rtf,.odt,.ods,.odp" aria-label="上传知识库文档" @change="upload" /></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="busy" class="knowledge-progress" role="status">正在处理，请稍候。上传时将依次保存原文、解析文档并生成向量…</p>
    <div class="knowledge-stats"><div><span class="knowledge-stat-icon">▧</span><div><small>全部文档</small><strong>{{ documents.length }}</strong></div></div><div><span class="knowledge-stat-icon green">✓</span><div><small>可检索文档</small><strong>{{ readyCount }}</strong></div></div><div><span class="knowledge-stat-icon">◈</span><div><small>知识分块</small><strong>{{ documents.filter(item => item.status === 'READY').reduce((sum, item) => sum + item.chunks, 0) }}</strong></div></div></div>
    <div class="knowledge-columns">
      <section class="knowledge-card"><div class="knowledge-card-heading"><h2>我的文档 <span class="badge">{{ documents.length }}</span></h2><button class="text-button" :disabled="busy" @click="refresh">刷新</button></div><input v-model="keyword" type="search" placeholder="搜索文档名称" aria-label="搜索文档名称" /><p class="hint">仅本人可见 · TXT / Markdown / PDF / Word / Excel / PowerPoint / HTML / RTF / OpenDocument · 单文件 ≤ 10 MB</p>
        <div class="knowledge-table-wrap"><table class="knowledge-table"><thead><tr><th>文档名称</th><th>状态</th><th>分块</th><th>操作</th></tr></thead><tbody><tr v-for="item in visibleDocuments" :key="item.id"><td><span class="knowledge-doc-icon">▤</span><strong>{{ item.filename }}</strong><small>{{ (item.size / 1024).toFixed(1) }} KB · {{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small></td><td><span :class="['knowledge-state', { ready: item.status === 'READY' }]">{{ statuses[item.status] || item.status }}</span><small v-if="item.errorMessage" class="knowledge-failure" role="status">{{ item.errorMessage }}</small></td><td>{{ item.chunks }}</td><td><button class="text-button" :disabled="busy" @click="action(() => knowledgeApi.download(item))">下载</button><button class="text-button knowledge-delete" :disabled="busy" @click="pendingDelete = item">删除</button></td></tr></tbody></table></div>
        <div v-if="!visibleDocuments.length" class="knowledge-empty"><span>▧</span><h3>{{ keyword ? '没有匹配的文档' : '建立你的专属知识库' }}</h3><p class="muted">{{ keyword ? '试试其他文档名称。' : '上传研究资料、产品文档或团队手册，让 Agent 基于真实资料回答。' }}</p></div>
      </section>
      <aside class="knowledge-card knowledge-search"><div class="knowledge-card-heading"><h2>检索测试</h2><span class="knowledge-tag">语义检索</span></div><p class="muted">输入问题，查看知识库中相关的原文片段。</p><form @submit.prevent="search"><label>你的问题<textarea v-model="query" maxlength="2000" rows="4" placeholder="例如：产品的核心能力有哪些？" required /></label><button class="primary wide" :disabled="busy || !query.trim()">检索知识库</button></form><p class="hint">仅展示达到最低相似度的片段，默认结合语义与关键词融合排序；分数不代表正确率。</p><div v-if="searched && !hits.length" class="knowledge-empty"><p>未找到达到相关性要求的片段，请尝试更具体的关键词，或确认已上传相关文档。</p></div><article v-for="(hit, index) in hits" :key="hit.documentId + ':' + hit.chunk" class="knowledge-hit"><div><strong>[{{ index + 1 }}] {{ hit.filename }}</strong><span>分块 {{ hit.chunk }} · 相似度 {{ hit.score.toFixed(3) }}<template v-if="hit.rerankScore != null"> · 融合分 {{ hit.rerankScore.toFixed(5) }}</template></span></div><p>{{ hit.text }}</p><button class="text-button" :disabled="busy" @click="action(() => knowledgeApi.download({ id: hit.documentId, filename: hit.filename }))">查看原文 ↓</button></article><div class="knowledge-tip">在会话工作台勾选输入框底部的“知识库”，再输入“检索知识库并回答…”，Agent 可按需调用检索工具，并以 [文件名#分块号] 标注来源。</div></aside>
    </div>
    <div v-if="pendingDelete" class="knowledge-overlay" role="presentation"><section class="knowledge-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-document-title"><h2 id="delete-document-title">删除文档</h2><p>确定删除“{{ pendingDelete.filename }}”？原文及知识分块将一并删除。</p><div><button class="secondary" :disabled="busy" @click="pendingDelete = null">取消</button><button class="primary" :disabled="busy" @click="remove">确认删除</button></div></section></div>
  </section>
</template>

<style scoped>
.knowledge-page{max-width:1440px;margin:auto;padding:28px 32px}.knowledge-heading,.knowledge-card-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.knowledge-heading h1{font-size:25px;display:flex;align-items:center;gap:12px}.knowledge-heading p{margin:8px 0 26px}.knowledge-tag{font-size:12px;background:#edf8f1;color:#269962;padding:6px 10px;border-radius:6px;font-weight:500}.knowledge-file{display:none}.knowledge-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:18px;margin:4px 0 26px}.knowledge-stats>div{display:flex;align-items:center;gap:16px;background:white;border:1px solid #e6eaf2;border-radius:12px;padding:22px}.knowledge-stat-icon{font-size:25px;background:#edf2ff;color:#4374f8;padding:10px 15px;border-radius:12px}.knowledge-stat-icon.green{color:#25ac70;background:#eaf9f0}.knowledge-stats small,.knowledge-stats strong{display:block}.knowledge-stats small{color:#8590a5;margin-bottom:8px}.knowledge-stats strong{font-size:26px}.knowledge-columns{display:grid;grid-template-columns:minmax(0,1.8fr) minmax(290px,1fr);gap:22px;align-items:start}.knowledge-card{background:white;border:1px solid #e5eaf2;border-radius:12px;padding:22px;min-width:0}.knowledge-card-heading{margin-bottom:18px}.knowledge-card h2{font-size:17px;margin:0}.knowledge-table-wrap{overflow:auto}.knowledge-table{width:100%;border-collapse:collapse;text-align:left;font-size:13px}.knowledge-table th{background:#f8faff;color:#8993a6;font-weight:500;white-space:nowrap}.knowledge-table td,.knowledge-table th{padding:15px 10px;border-bottom:1px solid #eef1f7}.knowledge-table td:first-child{min-width:140px;max-width:270px;overflow-wrap:anywhere}.knowledge-table td:last-child{white-space:nowrap}.knowledge-table small{display:block;color:#919aaa;font-size:11px;margin-top:8px}.knowledge-doc-icon{color:#4277ff;margin-right:8px}.knowledge-state{white-space:nowrap;font-size:12px;background:#fff4e4;color:#be7d20;padding:5px 8px;border-radius:5px}.knowledge-state.ready{background:#edf9f1;color:#249c63}.knowledge-failure{max-width:240px;white-space:normal;color:#b94747!important;line-height:1.6;overflow-wrap:anywhere}.knowledge-delete{margin-left:12px;color:#b94747}.knowledge-empty{text-align:center;padding:42px 16px}.knowledge-empty>span{font-size:40px;color:#7197fa}.knowledge-empty h3{font-size:16px}.knowledge-empty p{font-size:13px}.knowledge-search>p{font-size:13px}.knowledge-search textarea{resize:vertical;border:1px solid #dce2ed;border-radius:8px;font:inherit;padding:12px;width:100%;min-height:100px}.knowledge-search label{margin-top:18px}.knowledge-hit{border:1px solid #e4eaf5;border-radius:8px;padding:14px;margin-top:14px}.knowledge-hit strong{font-size:13px;overflow-wrap:anywhere}.knowledge-hit span{display:block;font-size:11px;color:#8793a8;margin-top:6px}.knowledge-hit p{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere;max-height:220px;overflow:auto}.knowledge-tip{font-size:12px;color:#73809a;background:#f5f8ff;padding:14px;border-radius:8px;line-height:1.8;margin-top:22px}.knowledge-progress{padding:12px;background:#edf3ff;color:#3869c8;border-radius:8px;font-size:13px}.knowledge-overlay{position:fixed;inset:0;background:#17233d66;display:grid;place-items:center;z-index:100;padding:20px}.knowledge-dialog{background:white;border-radius:12px;max-width:460px;padding:28px;overflow-wrap:anywhere}.knowledge-dialog>div{display:flex;justify-content:flex-end;gap:12px}@media(max-width:1100px){.knowledge-columns{grid-template-columns:1fr}}@media(max-width:640px){.knowledge-page{padding:20px 14px}.knowledge-stats{gap:8px}.knowledge-stats>div{padding:14px 10px;gap:6px}.knowledge-stat-icon{display:none}.knowledge-stats small{font-size:11px}.knowledge-card{padding:15px}.knowledge-heading h1{font-size:22px}.knowledge-heading .primary{padding:10px 12px;white-space:nowrap}}
</style>
