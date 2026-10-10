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
    let configured = true;
    let success = true;
    const configuration = () => ({
      configured, modelName: 'self-test-model', maxIterations: 6, maxToolCalls: 10, taskTimeoutSeconds: 90,
      tools: [{ name: 'calculator', description: '计算器' }, { name: 'current_time', description: '当前时间' }],
    });
    await page.route('**/api/**', async route => {
      const request = route.request();
      const endpoint = new URL(request.url()).pathname;
      let data;
      if (endpoint === '/api/auth/login') data = { token: 'test-token', user: { id: 7, username: 'alice', nickname: '研究员', role: 'USER', status: 1 } };
      else if (endpoint === '/api/react/config') data = configuration();
      else if (endpoint === '/api/react/runs') {
        runCount++;
        assert.deepEqual(Object.keys(request.postDataJSON()), ['goal']);
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
      } else if (endpoint === '/api/users/me') data = { id: 7, username: 'alice', nickname: '研究员', role: 'USER', status: 1 };
      await route.fulfill({ json: { code: 'OK', message: '操作成功', data } });
    });
    await page.goto('http://127.0.0.1:' + server.address().port);
    await page.getByLabel('用户名', { exact: true }).fill('alice');
    await page.getByLabel('密码', { exact: true }).fill('Password123!');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('heading', { name: '会话工作台', exact: true }).waitFor();
    assert.equal(await page.getByRole('checkbox').count(), 0);
    await page.getByText('Agent 会根据任务自动选择并调用可用工具，无需手动选择。', { exact: true }).waitFor();
    await page.getByRole('button', { name: '多步计算' }).click();
    await page.getByRole('button', { name: '开始执行' }).click();
    await page.getByRole('heading', { name: '执行完成', exact: true }).waitFor();
    await page.getByText('128加256得到384，再乘以3，最终结果为1152。', { exact: true }).waitFor();
    assert.equal(runCount, 1);
    assert.equal(await page.locator('.react-step').count(), 5);
    await page.screenshot({ path: 'docs/images/react-workbench-v1.png', fullPage: true });
    success = false;
    await page.getByRole('button', { name: '开始执行' }).click();
    await page.getByRole('heading', { name: '达到执行上限', exact: true }).waitFor();
    await page.getByText('达到模型迭代次数上限').waitFor();
    configured = false;
    await page.getByRole('button', { name: '个人中心' }).click();
    await page.getByRole('button', { name: '会话工作台' }).click();
    await page.getByText('模型尚未配置', { exact: false }).waitFor();
    assert.ok(await page.getByRole('button', { name: '开始执行' }).isDisabled());
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    assert.deepEqual(errors, []);
    console.log('ReAct browser smoke passed: authorized payload, success trace, budget stop, unconfigured model, mobile layout. API responses simulated.');
  } finally {
    await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
