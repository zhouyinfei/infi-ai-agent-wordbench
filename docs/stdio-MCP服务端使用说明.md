# stdio MCP 服务端

项目提供独立入口 `com.infi.agent.mcp.server.McpStdioServer`，作为本地 MCP 子进程开放两个真实工具：

| 工具名称 | 参数 | 功能 |
| --- | --- | --- |
| `calculator` | `left`、`right`：数字；`operator`：`ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE` | 有界十进制四则运算，除法采用 DECIMAL128 |
| `current_time` | 可选 `timezone`：IANA 时区字符串 | 查询当前时间，默认 `Asia/Shanghai` |

工具执行复用现有 Java 工具及注册表的严格参数校验。服务端不启动 Spring 容器、数据库、Redis、模型客户端或 HTTP 端口。它和工作台后端是两个独立进程。

## 构建与启动

在项目根目录使用 JDK 17 或以上版本执行：

```powershell
mvn package
```

构建得到两个可执行文件：

- `target/infi-ai-agent-0.1.0.jar`：原有 Spring Boot 后端。
- `target/infi-ai-agent-0.1.0-mcp-stdio.jar`：独立 stdio MCP 服务端。

可手动运行服务端检查协议：

```powershell
java -jar target/infi-ai-agent-0.1.0-mcp-stdio.jar
```

启动后等待标准输入是正常行为，不显示启动横幅。依次输入以下三行 JSON，每个 JSON 对象各占一行：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"manual-test","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"calculator","arguments":{"left":0.1,"right":0.2,"operator":"ADD"}}}
```

第一行返回握手结果，第二行是通知、无响应，第三行返回 `0.3`。标准输入关闭时退出，也可按 Ctrl+C 终止。

## 接入工作台

在被 Git 忽略的 `src/main/resources/application-local.yml` 中合并以下配置。把 Java 和 JAR 路径替换为本机实际绝对路径；保留其他已有配置。

```yaml
app:
  mcp:
    enabled: true
    timeout: 30s
    servers:
      - name: infi-local
        transport: STDIO
        command:
          - C:/Java/jdk-17/bin/java.exe
          - -jar
          - D:/workspace/IDEA/ai/infi-ai-agent-wordbench/target/infi-ai-agent-0.1.0-mcp-stdio.jar
        allowed-tools:
          - calculator
          - current_time
```

然后启动工作台后端：

```powershell
mvn spring-boot:run
```

后端自动拉起 stdio 服务端，无需事先手动运行服务端。工具面板会出现 `mcp_infi-local_calculator` 和 `mcp_infi-local_current_time`。可提交“使用 mcp_infi-local_calculator 计算 0.1 加 0.2”查看真实调用轨迹；Multi-Agent 模式由 MCP 角色调用。手动启动的另一个 stdio 进程不会被后端接管。

## 协议与扩展

服务端实现 [MCP 2025-11-25 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[stdio 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)和[工具协议](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)中的工具服务器子集：`initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`。只协商 `2025-11-25` 版本，新版客户端可回退到该版本。

- 使用 UTF-8，逐行读取 JSON-RPC，stdout 只承载协议响应；I/O 异常由 JVM 输出到 stderr。
- 完成初始化通知后才能发现或调用工具。未知方法、未知工具、非法 JSON 和参数结构错误返回 JSON-RPC 错误；除零、非法时区等业务错误返回 `isError: true`。
- 单条消息最多 400,000 个字符，超限后丢弃至下一行并返回错误，后续合法请求仍可处理。
- 单连接顺序执行短任务，不支持批量请求、资源、提示词、动态工具变更、长任务取消或 HTTP 传输。

新增业务工具时，在独立入口注册对应工具实现，并在 `listTools` 中添加对应 JSON Schema，再把原始工具名称加入客户端白名单。不要在该入口启动 Spring Boot 应用，也不要将日志写到 stdout。

验证命令：

```powershell
mvn -Dtest=McpStdioServerTest,McpIntegrationTest test
```

测试覆盖真实 Java 子进程与现有客户端的握手、发现、调用和失败观察，以及消息边界、通知和初始化状态。新增代码沿用阿里巴巴 Java 编程规约的命名、常量、注释、输入校验和资源管理要求；未执行 P3C 自动扫描。
