<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import AuthPanel from './components/AuthPanel.vue';
import ProfilePanel from './components/ProfilePanel.vue';
import UserManagement from './components/UserManagement.vue';
import ReactWorkbench from './components/ReactWorkbench.vue';
import { authApi, clearToken, getToken, saveToken, userApi } from './api/client';

const user = ref(null);
const activePage = ref('react');
const loading = ref(true);
const banner = ref('');

function resetAuth(message = '') {
  clearToken();
  user.value = null;
  activePage.value = 'react';
  banner.value = message;
}

function loggedIn(data) {
  saveToken(data.token);
  user.value = data.user;
  banner.value = '';
}

function expired() { resetAuth('登录已失效，请重新登录。'); }
async function logout() {
  try {
    await authApi.logout();
    resetAuth('你已退出登录。');
  } catch (exception) {
    banner.value = '退出失败：' + exception.message + '。请重试。';
  }
}

onMounted(async () => {
  window.addEventListener('auth-expired', expired);
  try { if (getToken()) user.value = await userApi.me(); }
  catch (exception) { banner.value = exception.message; }
  finally { loading.value = false; }
});
onUnmounted(() => window.removeEventListener('auth-expired', expired));
</script>

<template>
  <div v-if="loading" class="loading">正在加载工作台…</div>
  <template v-else-if="!user"><div v-if="banner" class="floating-notice" role="status">{{ banner }}</div><AuthPanel @logged-in="loggedIn" /></template>
  <div v-else class="app-shell">
    <header class="app-header"><div class="brand"><span class="brand-mark">A</span> Agent 工作台</div><div class="header-account"><span class="avatar">{{ user.nickname.slice(0, 1) }}</span><span>{{ user.nickname }}</span><button class="text-button" @click="logout">退出登录</button></div></header>
    <aside class="sidebar">
      <span class="nav-label">工作空间</span>
      <button :class="['nav-item', { active: activePage === 'react' }]" @click="activePage = 'react'"><span>◈</span> 会话工作台</button>
      <button disabled class="nav-item"><span>▤</span> 任务中心 <small>即将开放</small></button>
      <button disabled class="nav-item"><span>▧</span> 知识库 <small>即将开放</small></button>
      <span class="nav-label section-gap">账号与管理</span>
      <button :class="['nav-item', { active: activePage === 'profile' }]" @click="activePage = 'profile'"><span>◎</span> 个人中心</button>
      <button v-if="user.role === 'ADMIN'" :class="['nav-item', { active: activePage === 'users' }]" @click="activePage = 'users'"><span>♙</span> 用户管理</button>
      <div class="sidebar-footer"><span class="status-dot"></span> {{ user.role === 'ADMIN' ? '管理员工作空间' : '个人工作空间' }}</div>
    </aside>
    <main class="workspace"><p v-if="banner" class="error" role="alert">{{ banner }}</p><ReactWorkbench v-if="activePage === 'react'" /><ProfilePanel v-else-if="activePage === 'profile'" :key="user.id" :user="user" @updated="user = $event" @password-changed="resetAuth('密码已更新，请使用新密码登录。')" /><UserManagement v-else-if="user.role === 'ADMIN'" /></main>
  </div>
</template>
