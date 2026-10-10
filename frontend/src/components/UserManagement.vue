<script setup>
import { onMounted, ref } from 'vue';
import { userApi } from '../api/client';

const records = ref([]);
const total = ref(0);
const page = ref(1);
const keyword = ref('');
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const showCreate = ref(false);
const form = ref({ username: '', password: '', nickname: '', email: '' });

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const data = await userApi.list(page.value, keyword.value);
    records.value = data.records;
    total.value = data.total;
  } catch (exception) { error.value = exception.message; }
  finally { loading.value = false; }
}

function search() { page.value = 1; load(); }
function turn(delta) { page.value += delta; load(); }

async function create() {
  busy.value = true;
  error.value = '';
  try {
    await userApi.create(form.value);
    showCreate.value = false;
    form.value = { username: '', password: '', nickname: '', email: '' };
    page.value = 1;
    await load();
  } catch (exception) { error.value = exception.message; }
  finally { busy.value = false; }
}

async function toggle(user) {
  const verb = user.status === 1 ? '禁用' : '启用';
  if (!window.confirm('确认' + verb + '用户“' + user.username + '”？禁用会使其登录凭证失效。')) return;
  busy.value = true;
  error.value = '';
  try {
    await userApi.status(user.id, user.status === 1 ? 0 : 1);
    await load();
  } catch (exception) { error.value = exception.message; }
  finally { busy.value = false; }
}

onMounted(load);
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">ADMINISTRATION</span><h1>用户管理</h1><p class="muted">管理平台成员及账号状态。</p></div><button class="primary" @click="showCreate = !showCreate">{{ showCreate ? '收起' : '+ 创建用户' }}</button></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <section v-if="showCreate" class="card create-card">
    <h2>创建普通用户</h2>
    <form class="create-grid" @submit.prevent="create">
      <label>用户名<input v-model="form.username" required maxlength="32" autocomplete="off"></label>
      <label>昵称<input v-model="form.nickname" required maxlength="50"></label>
      <label>初始密码<input v-model="form.password" type="password" required minlength="8" maxlength="64" autocomplete="new-password"></label>
      <label>邮箱（选填）<input v-model="form.email" type="email" maxlength="100"></label>
      <div><button class="primary" :disabled="busy">确认创建</button></div>
    </form>
  </section>
  <section class="card">
    <form class="toolbar" @submit.prevent="search"><input v-model="keyword" maxlength="50" placeholder="搜索用户名或昵称" aria-label="搜索用户"><button class="secondary" :disabled="loading || busy">搜索</button><span class="muted">共 {{ total }} 位成员</span></form>
    <div class="table-wrap"><table><thead><tr><th>用户</th><th>邮箱</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
      <tbody><tr v-for="user in records" :key="user.id"><td><strong>{{ user.nickname }}</strong><small>@{{ user.username }}</small></td><td>{{ user.email || '—' }}</td><td>{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</td><td><span :class="['badge', user.status === 1 ? 'success' : 'disabled']">{{ user.status === 1 ? '已启用' : '已禁用' }}</span></td><td>{{ user.gmtCreate?.replace('T', ' ') }}</td><td><button v-if="user.role !== 'ADMIN'" class="text-button" :disabled="busy || loading" @click="toggle(user)">{{ user.status === 1 ? '禁用' : '启用' }}</button><span v-else class="muted">受保护</span></td></tr>
      <tr v-if="!records.length"><td colspan="6" class="empty">{{ loading ? '加载中…' : '没有找到用户' }}</td></tr></tbody></table></div>
    <div class="pagination"><button class="secondary" :disabled="page <= 1 || loading || busy" @click="turn(-1)">上一页</button><span>第 {{ page }} 页</span><button class="secondary" :disabled="page * 10 >= total || loading || busy" @click="turn(1)">下一页</button></div>
  </section>
</template>
