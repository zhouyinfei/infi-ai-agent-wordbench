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

    /**
     * 检查模型开关、API Key、模型名称及基础地址是否已填写；不发起联网验证。
     *
     * @return 是否具备发起模型请求的基本配置
     */
    @Override
    public boolean isConfigured() {
        return properties.isEnabled() && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getModelName() != null && !properties.getModelName().isBlank()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    /**
     * 通过 LangChain4j 调用 OpenAI 兼容模型，单次超时取配置值与任务剩余时间的较小值；禁用自动重试和请求响应日志。
     *
     * @param request 包含当前任务消息、工具描述和输出限制的模型请求
     * @param remainingTime 本次任务剩余的执行时间
     * @return 模型回复，可包含最终文本或工具调用请求
     * @throws IllegalStateException 模型请求超时配置为空、为零或为负
     */
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
