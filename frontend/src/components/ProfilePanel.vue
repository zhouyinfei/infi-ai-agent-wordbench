<script setup>
import { ref } from 'vue';
import { userApi } from '../api/client';

const props = defineProps({ user: { type: Object, required: true } });
const emit = defineEmits(['updated', 'password-changed']);
const profile = ref({ nickname: props.user.nickname, email: props.user.email || '' });
const password = ref({ oldPassword: '', newPassword: '', confirm: '' });
const busy = ref(false);
const message = ref('');
const error = ref('');

async function saveProfile() {
  busy.value = true;
  message.value = '';
  error.value = '';
  try {
    emit('updated', await userApi.update(profile.value));
    message.value = '个人资料已保存';
  } catch (exception) { error.value = exception.message; }
  finally { busy.value = false; }
}

async function changePassword() {
  error.value = '';
  message.value = '';
  if (password.value.newPassword !== password.value.confirm) {
    error.value = '两次输入的新密码不一致';
    return;
  }
  busy.value = true;
  try {
    await userApi.password({ oldPassword: password.value.oldPassword, newPassword: password.value.newPassword });
    emit('password-changed');
  } catch (exception) { error.value = exception.message; }
  finally { busy.value = false; }
}
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">ACCOUNT</span><h1>个人中心</h1><p class="muted">管理你的个人资料，保持账号安全。</p></div><span class="badge success">账号已启用</span></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p>
  <div class="profile-grid">
    <section class="card">
      <h2>个人资料</h2><p class="muted">这些信息用于工作台中的身份展示。</p>
      <form @submit.prevent="saveProfile">
        <label>用户名<input :value="user.username" disabled><span class="hint">用户名作为登录标识，暂不支持修改。</span></label>
        <label>昵称<input v-model="profile.nickname" required maxlength="50" autocomplete="nickname"></label>
        <label>邮箱<input v-model="profile.email" type="email" maxlength="100" autocomplete="email" placeholder="选填"></label>
        <button class="primary" :disabled="busy">保存资料</button>
      </form>
    </section>
    <section class="card">
      <h2>账号安全</h2><p class="muted">修改密码后，所有已登录凭证失效。</p>
      <form @submit.prevent="changePassword">
        <label>当前密码<input v-model="password.oldPassword" type="password" required maxlength="64" autocomplete="current-password"></label>
        <label>新密码<input v-model="password.newPassword" type="password" required minlength="8" maxlength="64" autocomplete="new-password"></label>
        <label>确认新密码<input v-model="password.confirm" type="password" required minlength="8" maxlength="64" autocomplete="new-password"></label>
        <button class="secondary" :disabled="busy">更新密码</button>
      </form>
    </section>
  </div>
</template>
