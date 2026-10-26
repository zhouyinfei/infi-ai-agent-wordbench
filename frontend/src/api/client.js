const TOKEN_KEY = 'infi-agent-token';

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function saveToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

export async function request(path, options = {}) {
  const token = getToken();
  const headers = { 'Content-Type': 'application/json', ...options.headers };
  if (options.body instanceof FormData) delete headers['Content-Type'];
  if (token) headers.Authorization = 'Bearer ' + token;
  const response = await fetch('/api' + path, { ...options, headers });
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error('服务响应异常，请稍后重试');
  }
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login') {
      clearToken();
      window.dispatchEvent(new Event('auth-expired'));
    }
    throw new Error(body.message || '请求失败');
  }
  return body.data;
}

export const authApi = {
  login: (data) => request('/auth/login', { method: 'POST', body: JSON.stringify(data) }),
  register: (data) => request('/auth/register', { method: 'POST', body: JSON.stringify(data) }),
  logout: () => request('/auth/logout', { method: 'POST' }),
};

export const userApi = {
  me: () => request('/users/me'),
  update: (data) => request('/users/me', { method: 'PUT', body: JSON.stringify(data) }),
  password: (data) => request('/users/me/password', { method: 'PUT', body: JSON.stringify(data) }),
  list: (page, keyword) => request('/admin/users?' + new URLSearchParams({ page, size: 10, keyword })),
  create: (data) => request('/admin/users', { method: 'POST', body: JSON.stringify(data) }),
  status: (id, status) => request('/admin/users/' + id + '/status', { method: 'PUT', body: JSON.stringify({ status }) }),
};

export const reactApi = {
  models: () => request('/react/models'),
  configuration: () => request('/react/config'),
  run: (data) => request('/react/runs', { method: 'POST', body: JSON.stringify(data) }),
};


export const planExecuteApi = {
  run: (data) => request('/plan-execute/runs', { method: 'POST', body: JSON.stringify(data) }),
};


export const multiAgentApi = {
  configuration: () => request('/multi-agent/config'),
  run: (data) => request('/multi-agent/runs', { method: 'POST', body: JSON.stringify(data) }),
};

export const agentTaskApi = {
  submit: (data) => request('/agent-tasks', { method: 'POST', body: JSON.stringify(data) }),
  get: (id) => request('/agent-tasks/' + encodeURIComponent(id)),
  active: () => request('/agent-tasks'),
  decide: (id, data) => request('/agent-tasks/' + encodeURIComponent(id) + '/decision', { method: 'POST', body: JSON.stringify(data) }),
};


export const conversationApi = {
  rename: (id, title) => request('/conversations/' + id, { method: 'PATCH', body: JSON.stringify({ title }) }),
  list: () => request('/conversations'),
  create: (title = '新会话') => request('/conversations', { method: 'POST', body: JSON.stringify({ title }) }),
  history: (id, beforeId) => request('/conversations/' + id + '/messages' + (beforeId ? '?beforeId=' + beforeId : '')),
  clear: (id) => request('/conversations/' + id + '/messages', { method: 'DELETE' }),
  delete: (id) => request('/conversations/' + id, { method: 'DELETE' }),
};

export const knowledgeApi = {
  list: () => request('/knowledge/documents'),
  upload: (file) => {
    const body = new FormData();
    body.append('file', file);
    return request('/knowledge/documents', { method: 'POST', body });
  },
  delete: (id) => request('/knowledge/documents/' + encodeURIComponent(id), { method: 'DELETE' }),
  search: (query, limit = 5) => request('/knowledge/search', { method: 'POST', body: JSON.stringify({ query, limit }) }),
  download: async (document) => {
    const response = await fetch('/api/knowledge/documents/' + encodeURIComponent(document.id) + '/content', {
      headers: { Authorization: 'Bearer ' + getToken() },
    });
    if (!response.ok) {
      if (response.status === 401) {
        clearToken();
        window.dispatchEvent(new Event('auth-expired'));
      }
      const body = await response.json().catch(() => ({}));
      throw new Error(body.message || '下载失败');
    }
    const url = URL.createObjectURL(await response.blob());
    const link = window.document.createElement('a');
    link.href = url;
    link.download = document.filename;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};
