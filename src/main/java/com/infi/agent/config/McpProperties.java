package com.infi.agent.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** MCP 客户端配置，仅允许运维通过服务端配置注册可信服务器。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {
    private Boolean enabled = Boolean.FALSE;
    private Duration timeout = Duration.ofSeconds(30);
    private List<Server> servers = new ArrayList<>();

    /** 单个服务器的连接信息及明确授权的工具名称。 */
    @Getter
    @Setter
    public static class Server {
        private String name;
        private Transport transport = Transport.STREAMABLE_HTTP;
        private String url;
        private Map<String, String> headers = new LinkedHashMap<>();
        private List<String> command = new ArrayList<>();
        private Map<String, String> environment = new LinkedHashMap<>();
        private List<String> allowedTools = new ArrayList<>();
    }

    /** 支持的 MCP 标准传输方式。 */
    public enum Transport {
        STREAMABLE_HTTP,
        STDIO
    }
}
