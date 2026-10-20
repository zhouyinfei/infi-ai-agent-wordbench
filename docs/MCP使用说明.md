# MCP 使用说明

平台作为 MCP 客户端，连接管理员配置的可信 MCP 服务器，将其工具加入 ReAct、Plan-and-Execute 和 Multi-Agent 的现有执行链。支持 Streamable HTTP 和 stdio，客户端协议实现使用 LangChain4j MCP SDK。项目另提供可独立启动的 [stdio MCP 服务端](stdio-MCP服务端使用说明.md)，开放计算及当前时间工具。当前不提供资源浏览或提示词管理接口。

## 配置

默认关闭，不连接外部服务，也不启动子进程。在被 Git 忽略的 `src/main/resources/application-local.yml` 中合并以下配置，重启后生效：

```yaml
app:
  mcp:
    enabled: true
    timeout: 30s
    servers:
      - name: knowledge
        transport: STREAMABLE_HTTP
        url: http://127.0.0.1:3001/mcp
        headers:
          Authorization: Bearer ${KNOWLEDGE_MCP_TOKEN}
        allowed-tools:
          - search
      - name: local
        transport: STDIO
        # 使用机器上已安装的可执行文件及脚本绝对路径；参数分项传入。
        command:
          - C:/tools/node/node.exe
          - C:/tools/mcp-server/index.js
        environment:
          SERVICE_TOKEN: ${LOCAL_MCP_TOKEN}
        allowed-tools:
          - lookup
```

只配置实际使用的服务器。HTTP 服务无鉴权时可省略 `headers`。密钥使用环境变量，不提交到仓库，不放入 stdio 命令参数。stdio 子进程继承后端运行账户的权限和环境，文件权限由操作系统和 MCP 服务端控制。

服务器名称由 1–20 位字母、数字或短横线组成且不可重复。`allowed-tools` 是非空的原始工具名称白名单，不支持通配符；完整模型工具名最长 64 字符，仅允许字母、数字、下划线和短横线。连接、初始化与工具执行超时由 `timeout` 设置，范围为 1 毫秒至 5 分钟。

启动时发现并注册白名单工具。例如远程 `search` 在工作台展示为 `mcp_knowledge_search`，执行时自动转回原始名称；参数结构来自服务器。工具面板沿用已有配置接口展示这些工具，无需前端另行配置。新增或删除远程工具后需要重启以更新注册快照。

启用后，服务器不可达、白名单工具不存在或配置无效都会导致启动失败，防止悄悄缺少工具。初始化失败及应用关闭时会回收已创建连接；stdio 子进程由 SDK 关闭。

## 使用与边界

- ReAct 和 Plan-and-Execute 自动获得已注册的 MCP 工具。
- Multi-Agent 协调者可分配任务给 `MCP` 角色。只有该角色可以调用 MCP 工具，仍不能调用内置计算、文件或 HTTP 工具；其他角色不获得 MCP 权限。
- 调用沿用统一 JSON 参数校验、工具白名单、调用次数预算、Token 统计及执行轨迹。远程错误与超时显示为失败观察，不作为成功证据。
- MCP 请求通过配置的端点发出，与内置 HTTP GET 的域名白名单独立。服务器连接只能通过部署配置修改，前端和模型不能自行添加连接、命令或扩大工具授权。
- 单次远程调用受 MCP 超时限制，整体任务预算在调用前后检查；超时不代表远程已产生的副作用被撤销。MCP 返回的外部内容按不可信数据处理。

## 代码规约与验证

新增代码按[阿里巴巴 Java 开发手册](https://github.com/alibaba/p3c)约定采用明确命名、常量、构造器依赖注入、接口实现注解、Javadoc、参数前置校验与资源回收；日志不打印连接凭证或远程错误正文。没有对历史代码做全仓格式重写，也未将人工检查表述为 P3C 自动扫描结果。

`McpIntegrationTest` 使用本地 HTTP MCP 替身和真实 stdio 子进程验证协议握手、工具发现、参数与原始名称转发、鉴权请求头、远程错误、未授权工具拒绝、禁用状态和角色隔离。不需要外部服务或真实密钥。

```shell
mvn -Dtest=McpIntegrationTest test
mvn test
```

协议参考：[LangChain4j MCP 文档](https://docs.langchain4j.dev/tutorials/mcp/)。SDK 的 beta 版本号与核心库 1.22.0 对应，不单独升级核心库。
