# Infi AI Agent

## RAG 知识库

已接入 MySQL 文档元信息及状态管理、MinIO 原文存储、Elasticsearch 向量检索和知识库管理页面。先执行 `sql/003_knowledge.sql`。支持文档上传、失败原因展示、检索测试、来源引用及原文下载，三种 Agent 模式均可检索，暂不实现审批。配置与使用见 [RAG 使用说明](docs/RAG使用说明.md)。

前后端分离的 AI Agent 平台，当前已实现用户系统、ReAct、Plan-and-Execute 与同步 Multi-Agent 协作。

## 界面设计预览

下图展示项目的目标界面设计，部分功能将在后续阶段实现。

![Agent 工作台界面设计](docs/images/agent-workspace-preview.png)

## 环境

- JDK 17、Maven 3.6.3+
- Node.js 22.12+（或20.19+）
- MySQL 8、Redis 6+

## 初始化与启动

1. 执行 `sql/001_user.sql` 创建数据库和用户表，再在同一数据库执行 `sql/002_conversation.sql` 创建会话表。本机本次联调已完成会话表初始化。
2. 编辑 `src/main/resources/application-local.yml`，填写 MySQL、Redis 配置及首次管理员账号密码。该文件被 Git 忽略。
3. 首次在空用户表上启动前设置 `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD`，或填写 local 文件对应字段。密码遵循8–64位可见 ASCII 规则。管理员创建后清空初始化密码配置；不提供默认管理员或默认密码。
4. 后端：在项目根目录执行 `mvn spring-boot:run`，当前配置端口8126。
5. 前端：在 frontend 目录执行 `npm install`、`npm run dev`，访问 http://127.0.0.1:5173。
6. 登录管理员可管理普通用户；公开注册始终创建普通用户。

application.yml 保存普通配置及隐私项的环境变量占位备份。application-local.yml 保存本机真实值；其他环境通过环境变量覆盖。首次克隆时若 local 文件不存在，可按 application.yml 的对应项创建，或直接使用环境变量。真实凭证不应复制进受版本控制的备份文件。

开发时 Vite 将 /api 代理至后端；生产构建使用 `npm run build`，将 frontend/dist 部署到独立静态站点，并由网关将 /api 代理至后端。后端不托管 Vue 文件，不开放任意跨域。生产使用 HTTPS。

## 验证

后端在 JDK 17 下执行 `mvn test`；前端执行 `npm test` 和 `npm run build`。后端集成测试使用 H2 并模拟 Redis，不需要本地业务数据库，真实服务联调仍需上面的环境配置。

真实联调：填写本地配置后，在 backend 执行 `mvn -Dtest=RealInfrastructureIT test`。该测试使用本地 MySQL 凭证连接独立的 infi_agent_it 数据库（账号需具备建库权限），验证真实 Redis 有效期、退出、改密、权限与用户启停。测试清理自己创建的账号和 Redis 键，保留测试库表。可用 MYSQL_INTEGRATION_URL 指定已创建的独立测试数据库。

当前 ReAct 阶段已通过：27 项后端测试（含用户系统回归、ReAct 循环、接口权限及真实 LangChain4j 客户端对本地 HTTP 模型替身的协议验证）、5 项前端接口测试、前端生产构建和浏览器桌面/移动端检查。浏览器使用模拟接口；本阶段未调用真实外部模型。用户系统在上一阶段完成过真实 MySQL / Redis 联调。

## 项目结构

- src/main/java/com/infi/agent/controller：接口和参数校验
- src/main/java/com/infi/agent/service：业务接口
- src/main/java/com/infi/agent/service/impl：业务实现
- src/main/java/com/infi/agent/mapper：MyBatis 数据访问
- src/main/java/com/infi/agent/entity：DO
- src/main/java/com/infi/agent/dto、vo：输入与输出
- src/main/java/com/infi/agent/config、security、common：配置、认证与公共响应
- src/main/resources/mapper：SQL 映射
- frontend/src/api、components：前端接口封装与界面组件
- docs：需求、界面参考和用户系统接口说明

详见 [需求文档](docs/需求文档.md) 与 [用户系统接口](docs/用户系统接口.md)。后续按阶段实现 Agent 能力。

## MCP 工具接入

支持通过 Streamable HTTP 或 stdio 连接可信 MCP 服务器，配置工具白名单后自动接入 ReAct、Plan-and-Execute 和 Multi-Agent。默认关闭，配置与使用方式见 [MCP 使用说明](docs/MCP使用说明.md)。

项目自带独立 stdio MCP 服务端，开放计算和时间工具。执行 `mvn package` 后通过 `java -jar target/infi-ai-agent-0.1.0-mcp-stdio.jar` 启动，详见 [stdio MCP 服务端使用说明](docs/stdio-MCP服务端使用说明.md)。

## ReAct

新增文件创建、读取、覆盖写入、删除、目录浏览与 HTTP GET 工具；三种模式均已接入。配置和使用边界见 [工具使用说明](docs/工具使用说明.md)。

登录后打开“会话工作台”，可提交计算或当前时间任务并查看动作与观察轨迹。首先在 src/main/resources/application-local.yml 配置 app.ai 的 enabled、base-url、model-name、api-key；模型须支持工具调用。本轮仅接入一个 OpenAI 兼容模型，不提供默认模拟模式，未配置时明确提示。

使用说明、接口、边界与自测见 [ReAct 使用说明](docs/ReAct使用说明.md)。


## Plan-and-Execute

会话工作台支持切换 ReAct 与 Plan-and-Execute。后者先生成计划，再按步骤自动调用工具，最后汇总结果；页面展示计划完成状态和实际执行轨迹，整次任务共用预算。详见 [Plan-and-Execute 使用说明](docs/Plan-and-Execute使用说明.md)。


执行结果中的“本次执行”会显示模型服务返回的累计输入、输出和总 Token 用量。两种模式均统计所有模型调用；未返回用量时显示“未提供”，部分统计会标明覆盖次数，不估算实际账单。Token 统计回归后，65 项后端测试、6 项前端测试、生产构建及浏览器检查均通过。


## Multi-Agent 协作

工作台新增 Multi-Agent 模式：协调 Agent 分工，计算、时间和分析 Agent 在独立上下文中顺序执行，审校 Agent 核对真实证据并汇总。角色工具权限由后端固定，整次协作共用预算及 Token 统计。详见 [Multi-Agent 使用说明](docs/Multi-Agent使用说明.md)。


Multi-Agent 已重构为统一 Agent 接口、独立角色实例与无状态通用执行器。协调服务只委派任务和消费结构化交付，专业 Agent 自己管理执行上下文与工具循环，支持按需替换专业实现。架构重构后103项后端测试通过，详见协作使用说明中的架构章节。


## 会话记忆

三种模式支持所属会话的近期公共对话记忆。MySQL持久化完整轮次和有限窗口，Redis缓存带版本的窗口；项目内 RedisChatMemoryStore 实现 LangChain4j ChatMemoryStore 并接入 MessageWindowChatMemory。工作台支持新建、切换、刷新恢复、历史分页、清空及删除。详见 [会话记忆使用说明](docs/会话记忆使用说明.md)。
