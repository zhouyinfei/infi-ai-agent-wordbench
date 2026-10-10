package com.infi.agent.react.model;

import com.infi.agent.config.AiModelProperties;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** LangChain4j 原生工具调用；禁用自动重试及请求响应日志。 */
@Component
@RequiredArgsConstructor
public class OpenAiReactModelGateway implements ReactModelGateway {
    private final AiModelProperties properties;

    @Override
    public boolean isConfigured() {
        return properties.isEnabled() && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getModelName() != null && !properties.getModelName().isBlank()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    @Override
    public ChatResponse chat(ChatRequest request, Duration remainingTime) {
        Duration configuredTimeout = properties.getRequestTimeout();
        if (configuredTimeout == null || configuredTimeout.isNegative() || configuredTimeout.isZero()) {
            throw new IllegalStateException("模型超时配置不合法");
        }
        Duration timeout = configuredTimeout.compareTo(remainingTime) < 0 ? configuredTimeout : remainingTime;
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .temperature(0.0)
                .maxRetries(0)
                .timeout(timeout)
                .logRequests(false)
                .logResponses(false)
                .build().chat(request);
    }
}
