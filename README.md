# Infi AI Agent

前后端分离的 AI Agent 平台，当前已实现用户系统与 ReAct 同步执行闭环。

## 环境

- JDK 17、Maven 3.6.3+
- Node.js 22.12+（或20.19+）
- MySQL 8、Redis 6+

## 初始化与启动

1. 执行 `sql/001_user.sql` 创建数据库和用户表。
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

## ReAct

登录后打开“会话工作台”，可提交计算或当前时间任务并查看动作与观察轨迹。首先在 src/main/resources/application-local.yml 配置 app.ai 的 enabled、base-url、model-name、api-key；模型须支持工具调用。本轮仅接入一个 OpenAI 兼容模型，不提供默认模拟模式，未配置时明确提示。

使用说明、接口、边界与自测见 [ReAct 使用说明](docs/ReAct使用说明.md)。
