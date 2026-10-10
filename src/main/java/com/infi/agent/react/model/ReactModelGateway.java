package com.infi.agent.react.model;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;

/** 模型调用边界，本阶段仅提供一个 OpenAI 兼容实现。 */
public interface ReactModelGateway {
    /**
     * 检查当前模型适配器是否具备调用所需的配置。
     *
     * @return 模型是否已启用且具备必要配置
     */
    boolean isConfigured();
    /**
     * 在任务剩余时间约束下发送模型请求，返回文本或原生工具调用信息。
     *
     * @param request 任务消息、可用工具描述及输出限制
     * @param remainingTime 当前任务的剩余执行时间
     * @return 模型回复；调用失败时由实现抛出异常
     */
    ChatResponse chat(ChatRequest request, Duration remainingTime);
}
