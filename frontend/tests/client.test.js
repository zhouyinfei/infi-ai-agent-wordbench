import { beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { request, saveToken, getToken, conversationApi, multiAgentApi, planExecuteApi, reactApi } from '../src/api/client.js';

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


test('Plan-and-Execute submits only goal to its authenticated endpoint', async () => {
  saveToken('plan-token');
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/plan-execute/runs');
    assert.equal(options.headers.Authorization, 'Bearer plan-token');
    assert.deepEqual(JSON.parse(options.body), { goal: '先加再乘' });
    return { ok: true, json: async () => ({ data: { status: 'SUCCEEDED', answer: '1152' } }) };
  };
  assert.equal((await planExecuteApi.run({ goal: '先加再乘' })).answer, '1152');
});


test('Multi-Agent uses its configuration and authenticated goal endpoint', async () => {
  saveToken('multi-token');
  globalThis.fetch = async (url, options) => {
    assert.equal(options.headers.Authorization, 'Bearer multi-token');
    if (url === '/api/multi-agent/config') {
      return { ok: true, json: async () => ({ data: { maxIterations: 12 } }) };
    }
    assert.equal(url, '/api/multi-agent/runs');
    assert.deepEqual(JSON.parse(options.body), { goal: '协作任务' });
    return { ok: true, json: async () => ({ data: { status: 'SUCCEEDED', answer: '协作结果' } }) };
  };
  assert.equal((await multiAgentApi.configuration()).maxIterations, 12);
  assert.equal((await multiAgentApi.run({ goal: '协作任务' })).answer, '协作结果');
});


test('conversation API sends authenticated create, history cursor and delete requests', async () => {
  saveToken('conversation-token');
  const calls = [];
  globalThis.fetch = async (url, options) => {
    assert.equal(options.headers.Authorization, 'Bearer conversation-token');
    calls.push({ url, method: options.method, body: options.body });
    return { ok: true, json: async () => ({ data: {} }) };
  };
  await conversationApi.list();
  await conversationApi.create('记忆');
  await conversationApi.history('id', 10);
  await conversationApi.clear('id');
  await conversationApi.delete('id');
  assert.deepEqual(calls.map(call => call.url), ['/api/conversations', '/api/conversations',
    '/api/conversations/id/messages?beforeId=10', '/api/conversations/id/messages', '/api/conversations/id']);
  assert.equal(calls[1].body, JSON.stringify({ title: '记忆' }));
  assert.equal(calls[3].method, 'DELETE');
});
