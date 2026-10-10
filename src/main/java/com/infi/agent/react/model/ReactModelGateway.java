package com.infi.agent.react.model;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;

/** 模型调用边界，本阶段仅提供一个 OpenAI 兼容实现。 */
public interface ReactModelGateway {
    boolean isConfigured();
    ChatResponse chat(ChatRequest request, Duration remainingTime);
}
