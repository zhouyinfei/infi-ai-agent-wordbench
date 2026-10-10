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
  configuration: () => request('/react/config'),
  run: (data) => request('/react/runs', { method: 'POST', body: JSON.stringify(data) }),
};


export const planExecuteApi = {
  run: (data) => request('/plan-execute/runs', { method: 'POST', body: JSON.stringify(data) }),
};
