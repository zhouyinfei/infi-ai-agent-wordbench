package com.infi.agent.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 默认连接及命名模型目录，密钥仅供服务端使用。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai")
public class AiModelProperties {
    private String defaultModel = "";
    private java.util.Map<String, AiModelProperties> models = new java.util.LinkedHashMap<>();
    private String provider = "openai-compatible";
    private boolean enabled;
    private String baseUrl = "https://api.openai.com/v1";
    private String modelName = "";
    private String apiKey = "";
    private Duration requestTimeout = Duration.ofSeconds(30);
}
