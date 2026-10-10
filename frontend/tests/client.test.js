import { beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { request, saveToken, getToken, reactApi } from '../src/api/client.js';

const memory = new Map();
let events;
beforeEach(() => {
  memory.clear();
  events = [];
  globalThis.sessionStorage = {
    getItem: (key) => memory.get(key) || null,
    setItem: (key, value) => memory.set(key, value),
    removeItem: (key) => memory.delete(key),
  };
  globalThis.window = { dispatchEvent: (event) => events.push(event.type) };
});

test('authenticated request adds bearer and returns data', async () => {
  saveToken('example-token');
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/users/me');
    assert.equal(options.headers.Authorization, 'Bearer example-token');
    return { ok: true, json: async () => ({ data: { id: 1 } }) };
  };
  assert.deepEqual(await request('/users/me'), { id: 1 });
});

test('expired auth clears credential and notifies shell', async () => {
  saveToken('expired');
  globalThis.fetch = async () => ({ ok: false, status: 401, json: async () => ({ message: '请先登录' }) });
  await assert.rejects(request('/users/me'), /请先登录/);
  assert.equal(getToken(), null);
  assert.deepEqual(events, ['auth-expired']);
});

test('wrong login password does not trigger global expiry event', async () => {
  globalThis.fetch = async () => ({ ok: false, status: 401, json: async () => ({ message: '密码错误' }) });
  await assert.rejects(request('/auth/login'), /密码错误/);
  assert.deepEqual(events, []);
});

test('ReAct submits only goal with bearer token for automatic tool selection', async () => {
  saveToken('react-token');
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/react/runs');
    assert.equal(options.headers.Authorization, 'Bearer react-token');
    assert.deepEqual(JSON.parse(options.body), { goal: '计算' });
    return { ok: true, json: async () => ({ data: { status: 'SUCCEEDED', answer: '42' } }) };
  };
  assert.equal((await reactApi.run({ goal: '计算' })).answer, '42');
});

test('missing model is a service error and does not erase login', async () => {
  saveToken('react-token');
  globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ message: '模型尚未配置' }) });
  await assert.rejects(reactApi.run({ goal: '计算' }), /模型尚未配置/);
  assert.equal(getToken(), 'react-token');
  assert.deepEqual(events, []);
});
