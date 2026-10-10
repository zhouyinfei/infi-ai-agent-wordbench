<script setup>
import { ref } from 'vue';
import { authApi } from '../api/client';

const emit = defineEmits(['logged-in']);
const mode = ref('login');
const form = ref({ username: '', password: '', nickname: '', email: '' });
const busy = ref(false);
const error = ref('');
const notice = ref('');

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login';
  error.value = '';
  notice.value = '';
  form.value.password = '';
}

async function submit() {
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    if (mode.value === 'register') {
      await authApi.register(form.value);
      mode.value = 'login';
      form.value.password = '';
      notice.value = '注册成功，请登录。';
    } else {
      emit('logged-in', await authApi.login({ username: form.value.username, password: form.value.password }));
      form.value.password = '';
    }
  } catch (exception) {
    error.value = exception.message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="auth-layout">
    <section class="auth-intro">
      <div class="brand"><span class="brand-mark">A</span> Agent 工作台</div>
      <div><span class="eyebrow">你的智能工作入口</span><h1>让目标成为<br>有序的行动。</h1><p>连接知识与工具，在每一步保持清晰、可控。</p></div>
      <span class="intro-footer">知识 · 协作 · 行动</span>
    </section>
    <section class="auth-form-wrap">
      <form class="auth-form" @submit.prevent="submit">
        <span class="eyebrow">欢迎使用</span><h2>{{ mode === 'login' ? '登录你的账号' : '创建新账号' }}</h2>
        <p class="muted">{{ mode === 'login' ? '登录后管理个人资料和账号安全。' : '注册账号，开始使用工作台。' }}</p>
        <label>用户名<input v-model="form.username" required maxlength="32" autocomplete="username" placeholder="请输入用户名"></label>
        <label v-if="mode === 'register'">昵称<input v-model="form.nickname" required maxlength="50" autocomplete="nickname" placeholder="怎么称呼你"></label>
        <label v-if="mode === 'register'">邮箱（选填）<input v-model="form.email" type="email" maxlength="100" autocomplete="email" placeholder="name@example.com"></label>
        <label>密码<input v-model="form.password" type="password" required :minlength="mode === 'register' ? 8 : 1" maxlength="64" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" placeholder="请输入密码"></label>
        <p v-if="mode === 'register'" class="hint">用户名：4–32位，以字母开头。密码：8–64位可见英文字符，不含空格。</p>
        <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
        <button class="primary wide" :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册' }}</button>
        <p class="auth-switch">{{ mode === 'login' ? '还没有账号？' : '已有账号？' }} <button class="text-button" type="button" :disabled="busy" @click="switchMode">{{ mode === 'login' ? '创建账号' : '返回登录' }}</button></p>
      </form>
    </section>
  </main>
</template>
