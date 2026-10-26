const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.argv[2] || 'playwright');
const root = path.resolve('frontend/dist');
const server = http.createServer((req, res) => {
  const filename = path.join(root, req.url.split('?')[0]);
  const file = fs.existsSync(filename) && fs.statSync(filename).isFile() ? filename : path.join(root, 'index.html');
  res.setHeader('Content-Type', { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' }[path.extname(file)] || 'application/octet-stream');
  res.end(fs.readFileSync(file));
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, executablePath: process.argv[3] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let runCount = 0;
    let conversationCount = 0;
    let conversationList = [];
    const histories = new Map();
    page.on('dialog', dialog => dialog.accept());
    let configured = true;
    let success = true;
    let usageMode = 'complete';
    let multiReviewApproved = true;
    const configuration = () => ({
      configured, modelName: 'self-test-model', maxIterations: 6, maxToolCalls: 10, taskTimeoutSeconds: 90,
      tools: [{ name: 'calculator', description: '计算器' }, { name: 'current_time', description: '当前时间' }],
    });
    await page.route('**/api/**', async route => {
      const request = route.request();
      const endpoint = new URL(request.url()).pathname;
      let data;
      if (endpoint === '/api/auth/login') data = { token: 'test-token', user: { id: 7, username: 'alice', nickname: '研究员', role: 'ADMIN', status: 1 } };
      else if (endpoint === '/api/conversations') {
        if (request.method() === 'POST') {
          const id = '00000000-0000-0000-0000-' + String(++conversationCount).padStart(12, '0');
          data = { id, title: request.postDataJSON().title || '新会话' };
          conversationList.unshift(data);
          histories.set(id, []);
        } else { data = conversationList; }
      } else if (endpoint.startsWith('/api/conversations/')) {
        const id = endpoint.split('/')[3];
        if (request.method() === 'PATCH') {
          const item = conversationList.find(item => item.id === id);
          item.title = request.postDataJSON().title;
          data = item;
        } else if (request.method() === 'DELETE') {
          histories.set(id, []);
          if (!endpoint.endsWith('/messages')) conversationList = conversationList.filter(item => item.id !== id);
          data = null;
        } else { data = { records: histories.get(id) || [], nextBeforeId: null }; }
      }
      else if (endpoint === '/api/react/config' || endpoint === '/api/multi-agent/config') data = { ...configuration(), maxIterations: endpoint === '/api/multi-agent/config' ? 12 : 6 };
      else if (endpoint === '/api/react/runs' || endpoint === '/api/plan-execute/runs' || endpoint === '/api/multi-agent/runs') {
        runCount++;
        assert.deepEqual(Object.keys(request.postDataJSON()), ['goal', 'conversationId']);
        assert.ok(histories.has(request.postDataJSON().conversationId));
        assert.equal(request.headers().authorization, 'Bearer test-token');
        data = { runId: 'react-ui-self-test', status: success ? 'SUCCEEDED' : 'LIMIT_REACHED',
          answer: success ? '128加256得到384，再乘以3，最终结果为1152。' : null,
          stopReason: success ? null : '达到模型迭代次数上限', modelCalls: 3, toolCalls: 2, durationMs: 580,
          steps: [
            { sequence: 1, iteration: 1, type: 'MODEL', status: 'SUCCESS', summary: '模型选择工具调用', durationMs: 150 },
            { sequence: 2, iteration: 1, type: 'TOOL', status: 'SUCCESS', toolName: 'calculator', arguments: { left: 128, operator: 'ADD', right: 256 }, observation: '384', durationMs: 1 },
            { sequence: 3, iteration: 2, type: 'MODEL', status: 'SUCCESS', summary: '模型选择工具调用', durationMs: 150 },
            { sequence: 4, iteration: 2, type: 'TOOL', status: 'SUCCESS', toolName: 'calculator', arguments: { left: 384, operator: 'MULTIPLY', right: 3 }, observation: '1152', durationMs: 1 },
            { sequence: 5, iteration: 3, type: 'MODEL', status: 'SUCCESS', summary: '模型给出最终回答', durationMs: 150 },
          ],
        };
        if (endpoint === '/api/plan-execute/runs' || endpoint === '/api/multi-agent/runs') {
          data.modelCalls = 6;
          data.steps = [
            { sequence: 1, iteration: 0, type: 'PLAN', status: 'SUCCESS', summary: '执行计划', arguments: ['计算128加256', '把结果乘以3'], durationMs: 0 },
            { sequence: 2, iteration: 1, type: 'STEP', status: 'SUCCESS', summary: '第一步完成', observation: '384', durationMs: 1 },
            { sequence: 3, iteration: 2, type: 'STEP', status: 'SUCCESS', summary: '第二步完成', observation: '1152', durationMs: 1 },
            { sequence: 4, iteration: 3, type: 'FINAL', status: 'SUCCESS', summary: '模型返回阶段结果', durationMs: 100 },
          ];
        }
        if (endpoint === '/api/multi-agent/runs') {
          data.modelCalls = 7;
          if (!multiReviewApproved) {
            data.status = 'FAILED';
            data.answer = null;
            data.stopReason = '审校未通过：结果缺少证据';
          }
          data.steps = [
            { sequence: 1, iteration: 0, type: 'COORDINATE', status: 'SUCCESS', summary: '协作分工', agentName: '协调 Agent', arguments: [{ agent: 'CALCULATOR', goal: '计算结果' }, { agent: 'ANALYST', goal: '分析结果' }], durationMs: 0 },
            { sequence: 2, iteration: 1, type: 'AGENT', status: 'SUCCESS', summary: '计算完成', agentName: '计算 Agent', observation: '1152', durationMs: 1 },
            { sequence: 3, iteration: 2, type: 'AGENT', status: 'SUCCESS', summary: '分析完成', agentName: '分析 Agent', observation: '1152', durationMs: 1 },
            { sequence: 4, iteration: 3, type: 'REVIEW', status: multiReviewApproved ? 'SUCCESS' : 'ERROR', summary: multiReviewApproved ? '审校通过' : '审校未通过', agentName: '审校 Agent', observation: '证据一致', durationMs: 1 },
          ];
        }
        data.tokenUsage = usageMode === 'none' ? null : {
          inputTokens: 10 * data.modelCalls, outputTokens: 5 * data.modelCalls,
          totalTokens: 15 * data.modelCalls,
          reportedModelCalls: usageMode === 'partial' ? data.modelCalls - 1 : data.modelCalls,
          complete: usageMode === 'complete',
        };
        const mode = endpoint.includes('multi-agent') ? 'MULTI_AGENT' : endpoint.includes('plan-execute') ? 'PLAN_EXECUTE' : 'REACT';
        histories.get(request.postDataJSON().conversationId).push({
          id: runCount, mode, goal: request.postDataJSON().goal, answer: data.answer, status: data.status,
        });
      } else if (endpoint === '/api/users/me') data = { id: 7, username: 'alice', nickname: '研究员', role: 'ADMIN', status: 1 };
      await route.fulfill({ json: { code: 'OK', message: '操作成功', data } });
    });
    await page.goto('http://127.0.0.1:' + server.address().port);
    await page.getByLabel('用户名', { exact: true }).fill('alice');
    await page.getByLabel('密码', { exact: true }).fill('Password123!');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('heading', { name: '会话工作台', exact: true }).waitFor();
    assert.equal(await page.getByRole('checkbox').count(), 0);
    assert.equal(await page.locator('.composer .mode-selector, .composer .tool-hint, .composer .mode-help').count(), 0);
    assert.equal(await page.locator('.chat-header').getByRole('combobox', { name: '执行模式', exact: true }).count(), 1);
    await page.getByRole('button', { name: '新建会话', exact: true }).click();
    assert.equal(conversationCount, 0);
    const inputBox = await page.locator('.composer').boundingBox();
    assert.ok(inputBox.height <= 100, '输入区应保持紧凑');
    assert.ok((await page.locator('.app-header').boundingBox()).height <= 50, '顶部导航应保持紧凑');
    const sendButton = await page.getByRole('button', { name: '发送', exact: true }).boundingBox();
    assert.ok(sendButton.width <= 100 && sendButton.height <= 40);
    assert.ok(sendButton.x >= inputBox.x && sendButton.y >= inputBox.y
      && sendButton.x + sendButton.width <= inputBox.x + inputBox.width
      && sendButton.y + sendButton.height <= inputBox.y + inputBox.height);
    assert.equal(await page.locator('#conversation-sidebar .model-settings').count(), 0);
    assert.equal(await page.locator('#conversation-sidebar').getByRole('button', { name: '新建会话', exact: true }).count(), 0);
    await page.getByRole('button', { name: '模型与工具', exact: true }).click();
    await page.getByRole('heading', { name: '模型与工具', exact: true }).waitFor();
    await page.getByText('最多模型调用', { exact: true }).waitFor();
    await page.getByText('calculator', { exact: true }).waitFor();
    await page.getByRole('button', { name: '新建会话', exact: true }).click();
    await page.getByRole('heading', { name: '会话工作台', exact: true }).waitFor();
    await page.getByRole('button', { name: '多步计算' }).click();
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByRole('heading', { name: '执行完成', exact: true }).waitFor();
    await page.locator('.react-answer').filter({ hasText: '最终结果为1152' }).waitFor();
    await page.locator('.conversation-history').waitFor();
    const firstConversationId = await page.locator('[aria-label="当前会话"]').inputValue();
    assert.equal(conversationList[0].title, '先计算128加256，再将结果乘以3');
    assert.equal(await page.locator('.recent-item.selected').textContent(), conversationList[0].title);
    assert.equal(runCount, 1);
    await page.getByTestId('run-token-summary').filter({ hasText: '45 Token' }).waitFor();
{
    const rowBeforeMenu = await page.locator('.recent-row.selected').boundingBox();
    await page.getByLabel('会话操作', { exact: true }).click();
    const rowAfterMenu = await page.locator('.recent-row.selected').boundingBox();
    assert.equal(rowAfterMenu.height, rowBeforeMenu.height, '菜单不能改变会话行高');
    assert.equal(rowAfterMenu.width, rowBeforeMenu.width, '菜单不能改变会话行宽');
    await page.getByRole('button', { name: '修改标题', exact: true }).click();
}
    await page.getByLabel('会话标题', { exact: true }).fill('我的计算任务');
    await page.getByRole('button', { name: '保存标题', exact: true }).click();
    await page.locator('.chat-header strong').filter({ hasText: '我的计算任务' }).waitFor();
    assert.equal(await page.locator('.react-answer').count(), 1);
    await page.getByTestId('token-usage-status').filter({ hasText: '统计完整' }).waitFor();
    assert.equal(await page.getByText('总 Token', { exact: true }).locator('xpath=following-sibling::dd[1]').textContent(), '45');
    assert.equal(await page.locator('.react-step').count(), 5);
    assert.equal(await page.locator('.react-answer').count(), 1);
    assert.equal(await page.getByLabel('任务目标', { exact: true }).inputValue(), '');
    await page.locator('.execution-details > summary').click();
    const composerRect = await page.locator('.composer').boundingBox();
    assert.ok(composerRect.y + composerRect.height <= 1000);
    const historyRect = await page.locator('.recent-conversations').boundingBox();
    assert.ok(historyRect.x < 260 && historyRect.y > 400);
    await page.screenshot({ path: 'docs/images/react-workbench-v1.png', fullPage: true });
    success = false;
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByRole('heading', { name: '达到执行上限', exact: true }).waitFor();
    await page.getByText('达到模型迭代次数上限').waitFor();
    success = true;
    await page.getByRole('combobox', { name: '执行模式', exact: true }).selectOption('PLAN_EXECUTE');
    assert.equal(await page.locator('.react-step').count(), 0);
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByRole('heading', { name: '执行计划', exact: true }).waitFor();
    assert.equal(await page.locator('.plan-list li').count(), 2);
    assert.equal(await page.locator('.plan-list .success').count(), 2);
    await page.getByText('汇总回答', { exact: true }).waitFor();
    assert.equal(await page.getByText('总 Token', { exact: true }).locator('xpath=following-sibling::dd[1]').textContent(), '90');
    await page.screenshot({ path: 'docs/images/plan-execute-workbench-v1.png', fullPage: true });
    usageMode = 'partial';
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByTestId('token-usage-status').filter({ hasText: '部分统计' }).waitFor();
    usageMode = 'none';
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByTestId('token-usage-status').filter({ hasText: '未返回' }).waitFor();
    assert.equal(await page.getByText('总 Token', { exact: true }).locator('xpath=following-sibling::dd[1]').textContent(), '未提供');
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    usageMode = 'complete';
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('combobox', { name: '执行模式', exact: true }).selectOption('MULTI_AGENT');
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByRole('heading', { name: '协作分工', exact: true }).waitFor();
    assert.equal(await page.locator('.collaboration-list li').count(), 2);
    assert.equal(await page.locator('.agent-role').count(), 4);
    await page.getByText('审校通过', { exact: true }).waitFor();
    assert.equal(await page.getByText('总 Token', { exact: true }).locator('xpath=following-sibling::dd[1]').textContent(), '105');
    await page.screenshot({ path: 'docs/images/multi-agent-workbench-v1.png', fullPage: true });
    multiReviewApproved = false;
    await page.getByLabel('任务目标', { exact: true }).fill('先计算128加256，再将结果乘以3');
    await page.getByRole('button', { name: '发送' }).click();
    await page.locator('.execution-details > summary').waitFor();
    await page.locator('.execution-details:not([open]) > summary').click();
    await page.getByRole('heading', { name: '执行失败', exact: true }).waitFor();
    await page.getByText('审校未通过：结果缺少证据', { exact: true }).waitFor();
    assert.equal(await page.locator('.collaboration-list .success').count(), 2);
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    await page.getByRole('button', { name: '最近会话', exact: true }).click();
    await page.getByRole('button', { name: '新建会话', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-history'));
    assert.equal(await page.getByLabel('任务目标', { exact: true }).inputValue(), '');
    await page.getByRole('button', { name: '最近会话', exact: true }).click();
    await page.locator('.recent-item').filter({ hasText: '我的计算任务' }).click();
    await page.locator('.conversation-history').waitFor();
    assert.ok(await page.locator('.conversation-turn').count() > 1);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.reload();
    await page.getByRole('heading', { name: '会话工作台', exact: true }).waitFor();
    await page.locator('.recent-item').filter({ hasText: '我的计算任务' }).click();
    await page.locator('.conversation-history').waitFor();
    // 覆盖管理员导航、多条历史和矮窗口：列表必须滚动，不能盖住操作按钮。
    for (let index = 0; index < 40; index++) {
      const id = '00000000-0000-0000-0000-' + String(++conversationCount).padStart(12, '0');
      conversationList.push({ id, title: '历史会话 ' + index });
      histories.set(id, []);
    }
    await page.reload();
    await page.locator('.recent-item').filter({ hasText: '我的计算任务' }).click();
    for (const viewport of [{ width: 1325, height: 700 }, { width: 900, height: 650 }]) {
      await page.setViewportSize(viewport);
      const list = await page.locator('.recent-list').boundingBox();
      const clear = await page.getByRole('button', { name: '清空会话', exact: true }).boundingBox();
      assert.ok(list.height > 20 && list.y + list.height <= clear.y, '会话列表不应覆盖清空按钮');
      assert.equal(await page.getByLabel('会话操作', { exact: true }).count(), 1);
      assert.ok(await page.locator('.recent-list').evaluate(element => element.scrollHeight > element.clientHeight));
  {
    const rowBeforeMenu = await page.locator('.recent-row.selected').boundingBox();
    await page.getByLabel('会话操作', { exact: true }).click();
    const rowAfterMenu = await page.locator('.recent-row.selected').boundingBox();
    assert.equal(rowAfterMenu.height, rowBeforeMenu.height, '菜单不能改变会话行高');
    assert.equal(rowAfterMenu.width, rowBeforeMenu.width, '菜单不能改变会话行宽');
    await page.getByRole('button', { name: '修改标题', exact: true }).click();
}
      const editor = await page.locator('.title-editor').boundingBox();
      const actions = await page.locator('.session-panel > .session-actions').boundingBox();
      assert.ok(editor.y + editor.height <= actions.y, '改名表单不应覆盖清空和删除按钮');
      await page.getByRole('button', { name: '取消', exact: true }).click();
    }
    await page.setViewportSize({ width: 1325, height: 700 });
    await page.screenshot({ path: 'docs/images/sidebar-layout-regression.png', fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('button', { name: '清空会话', exact: true }).click();
    await page.getByRole('button', { name: '确认清空', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-history'));
    await page.getByLabel('会话操作', { exact: true }).click();
    await page.getByRole('button', { name: '删除会话', exact: true }).click();
    const confirmation = page.getByRole('dialog');
    await confirmation.waitFor();
    const bounds = await confirmation.boundingBox();
    assert.ok(Math.abs(bounds.x + bounds.width / 2 - 720) < 2, '确认框应水平居中');
    assert.ok(Math.abs(bounds.y + bounds.height / 2 - 500) < 2, '确认框应垂直居中');
    await page.keyboard.press('Escape');
    await confirmation.waitFor({ state: 'hidden' });
    await page.getByLabel('会话操作', { exact: true }).click();
    await page.getByRole('button', { name: '删除会话', exact: true }).click();
    await page.getByRole('button', { name: '确认删除', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('[aria-label="当前会话"]').value === '');
    configured = false;
    await page.getByRole('button', { name: '个人中心' }).click();
    await page.getByRole('button', { name: '会话工作台' }).click();
    await page.getByText('模型尚未配置', { exact: false }).waitFor();
    assert.ok(await page.getByRole('button', { name: '发送' }).isDisabled());
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    assert.deepEqual(errors, []);
    console.log('Conversation browser smoke passed: new/switch/reload/clear/delete/history; Agent modes: ReAct/Plan-and-Execute/Multi-Agent, role handoffs, review rejection, token totals, complete/partial/missing usage, budget stop, unconfigured model, mobile layout. API responses simulated.');
  } finally {
    await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });


