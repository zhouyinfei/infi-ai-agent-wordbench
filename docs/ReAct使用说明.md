# ReAct 使用说明与自测

日期：2026-10-10。本阶段仅新增 ReAct，保留已有用户系统。

## 已实现范围

登录后进入“会话工作台”，提交一个自然语言目标。系统通过 LangChain4j 原生工具调用执行“模型决定动作 → 服务端执行工具 → 工具结果反馈给模型 → 继续或最终回答”的同步循环。

内置两个只读工具：

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| calculator | left、operator、right | 十进制加减乘除；operator 为 ADD、SUBTRACT、MULTIPLY、DIVIDE |
| current_time | timezone（选填） | 获取当前日期时间；默认 Asia/Shanghai，支持 IANA 时区 |

复合计算通过多轮工具调用完成，例如128加256，再将结果乘以3。支持直接回答、失败参数修正，以及同轮多个工具的顺序执行。

工作台展示最终回答、模型调用次数、工具参数、真实观察结果和耗时。轨迹只包含动作与观察，不保存或展示模型内部思维链。

本轮没有实现 MCP、RAG、长期或跨任务会话记忆、Plan-and-Execute、多 Agent、审批、联网、异步执行、并行工具执行、模型运行时切换和任务历史存储。一个请求中的临时消息只用于完成本次 ReAct 循环，请求结束后不持久化。

## 配置模型

在 src/main/resources/application-local.yml 的现有 app.ai 下填写：

```yaml
app:
  ai:
    enabled: true
    base-url: ${AI_BASE_URL:https://api.openai.com/v1}
    model-name: ${AI_MODEL:}
    api-key: ${AI_API_KEY:}
    request-timeout: 30s
```

model-name、api-key 需要填写服务商提供的实际值或设置对应环境变量。模型必须支持工具调用（Function Calling）；base-url 应为 OpenAI 兼容的 API 根路径，例如以 /v1 结尾，不能直接填写 /chat/completions。设置 enabled 后重启后端，再刷新前端配置。

application.yml 保留同结构占位备份。不通过接口返回密钥或连接地址，也不记录模型请求、响应及原始异常信息。未配置时页面提示未配置并禁用执行，后端返回 HTTP 503 / MODEL_NOT_CONFIGURED，不会自动生成伪造回答。

默认服务端预算在 application.yml 的 app.react 下配置：

- 最多6次模型调用、10次工具调用。
- 相同工具与相同参数字符串最多执行2次，后续停止循环。
- 任务总时限90秒，每次模型请求最多30秒且不超过剩余时限。
- 每次模型输出上限1024 Token；模型请求自动重试关闭。
- 工具只能来自当前任务白名单，参数必须通过服务端校验，计算器不执行脚本或字符串表达式。
- 计算器操作数绝对值不超过1E20，数字长度及小数尺度受限；除法使用 DECIMAL128 精度。

反向代理请求超时应大于任务时限，避免浏览器提前收到代理超时。本阶段同步请求没有持久化、任务恢复或取消接口。

## 接口

所有接口都要求已有登录凭证：Authorization: Bearer <token>。

### GET /api/react/config

返回 configured、modelName、maxIterations、maxToolCalls、taskTimeoutSeconds 和 tools，不返回密钥。

### POST /api/react/runs

```json
{
  "goal": "先计算128加256，再将结果乘以3，请使用工具并告诉我结果。",
  "allowedTools": ["calculator", "current_time"]
}
```

goal 必填，最多2000字。allowedTools 可省略（默认全部内置工具），显式指定时须包含1至2个不重复的支持工具；空列表、未知工具或重复工具被拒绝。模型提议的未授权工具不会执行，只会得到错误观察。

执行响应采用统一 ApiResponse 包装，data 包含 runId、status、answer、stopReason、modelCalls、toolCalls、durationMs、steps。

| status | 含义 |
| --- | --- |
| SUCCEEDED | 模型给出有效最终回答 |
| FAILED | 模型调用失败、空响应、截断或不合法调用结构 |
| TIMEOUT | 超过执行时限 |
| LIMIT_REACHED | 达到迭代、工具调用或重复调用上限 |

运行中失败与达到预算返回 HTTP 200，并通过 status 表示执行结果，保留已完成轨迹；输入校验失败返回400，未登录返回401，未配置返回503。SUCCEEDED 表示模型完成回答，不承诺对任意任务自动验证答案正确性。

## 自测方法

在项目根目录使用 JDK 17 执行：

```shell
mvn test
```

测试覆盖多轮计算及观察反馈、直接回答、工具失败修正、白名单、非法参数、数字精度、迭代与工具预算、重复循环、超时、模型失败、输出截断、登录权限及输入校验。原有用户系统测试同样运行。

OpenAiReactGatewayTest 使用本地 HTTP 模型替身，调用真实 LangChain4j OpenAiChatModel，验证工具定义、tool_call_id、观察消息及三轮计算；另验证HTTP 500不会自动重试或泄露服务商错误。无需真实API密钥，不访问付费模型服务。

前端在 frontend 目录执行 npm test、npm run build。浏览器检查脚本为 frontend/tests/react-ui-smoke.cjs，在项目根目录运行并提供已安装的 Playwright 模块路径与 Chromium 可执行文件路径：

```shell
node frontend/tests/react-ui-smoke.cjs <playwright模块路径> <chromium路径>
```

浏览器检查使用模拟接口，验证执行输入、登录凭证、成功轨迹、达到上限、未配置状态与移动端布局，不作为真实外部模型效果测试。真实服务商联调需配置实际模型后完成。

## 本次自测结果

- 后端共27项：ReAct 循环及工具14项、ReAct接口3项、本地HTTP模型协议2项、原有用户系统回归8项，全部通过。
- 前端接口封装5项全部通过，生产构建成功。
- 浏览器已通过成功轨迹、达到执行上限、模型未配置和移动端布局检查。
- 未调用真实外部模型，实际效果需配置真实服务后验证。

## 界面截图

以下为实际 Vue 工作台渲染，数据来自浏览器自测的模拟接口。

![ReAct 工作台](images/react-workbench-v1.png)

## 实现位置与参考

controller/ReactController → service/ReactService → service/impl/ReactServiceImpl；react/model 负责 LangChain4j 接口，react/tool 负责固定工具及白名单校验。由于本轮不持久化执行记录，不新增 mapper 或数据库表。

原生工具调用协议参考 [LangChain4j 官方工具文档](https://docs.langchain4j.dev/tutorials/tools/)。


### @Tool 工具注册

计算器和时间工具使用 LangChain4j 的 `@Tool`、`@P` 注解定义名称、描述和参数，`ToolSpecifications` 从注解生成发送给模型的工具协议。`ReactToolRegistry` 继续处理本次授权白名单和严格 JSON 校验，通过工具的 JSON 适配入口调用注解方法，以保留 BigDecimal 精度、参数边界和中文错误反馈。模型创建方式与手动 ReAct 循环保持一致；工具协议随每次 ChatRequest 传入模型。

新增工具时，实现 ReactTool 并交由 Spring 管理，声明一个公开的 @Tool 方法，在 execute(JsonNode) 中校验参数并调用该方法。当前没有启用 AiServices 自动执行循环。
