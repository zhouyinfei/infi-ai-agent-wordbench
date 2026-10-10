package com.infi.agent.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 单一模型连接配置，密钥仅供服务端使用。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai")
public class AiModelProperties {
    private boolean enabled;
    private String baseUrl = "https://api.openai.com/v1";
    private String modelName = "";
    private String apiKey = "";
    private Duration requestTimeout = Duration.ofSeconds(30);
}
