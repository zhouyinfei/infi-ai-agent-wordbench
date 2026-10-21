package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 单次任务独享的检索缓存和预算，跨计划步骤及协作角色共享，不跨请求保存。 */
public final class KnowledgeSearchScope {
    public static final String TOOL_NAME = "knowledge_search";
    public static final int DEFAULT_MAX_SEARCHES = 3;
    private final int maxSearches;
    private final boolean enabled;
    private final boolean networkEnabled;
    private final Map<String, ReactToolRegistry.ToolObservation> results = new HashMap<>();
    private int searches;
    private String stoppedReason;

    public KnowledgeSearchScope(int maxSearches) {
        this(maxSearches, true);
    }

    /** 检索授权由本次用户请求确定，不由模型、会话历史或缓存改变。 */
    public KnowledgeSearchScope(int maxSearches, boolean enabled) {
        this(maxSearches, enabled, true);
    }

    /** 本轮知识库与联网权限独立；内部兼容构造不用于解析客户端授权。 */
    public KnowledgeSearchScope(int maxSearches, boolean enabled, boolean networkEnabled) {
        if (maxSearches < 1 || maxSearches > 10) {
            throw new IllegalArgumentException("每任务知识库检索上限须为 1–10");
        }
        this.maxSearches = maxSearches;
        this.enabled = enabled;
        this.networkEnabled = networkEnabled;
    }

    /** 同轮批量调用也按顺序扣减；相同身份及问题复用已成功的结果，包括空结果。 */
    public synchronized ReactToolRegistry.ToolObservation execute(String owner, String query, JsonNode arguments,
                                                                  Supplier<ReactToolRegistry.ToolObservation> operation) {
        if (!enabled) {
            return new ReactToolRegistry.ToolObservation(false, arguments,
                    "用户未勾选知识库，本次任务禁止检索知识库。请直接回答或说明需要先勾选知识库。", "BLOCKED");
        }
        String key = owner + ":" + query.strip().replaceAll("\\s+", " ");
        ReactToolRegistry.ToolObservation cached = results.get(key);
        if (cached != null) {
            return new ReactToolRegistry.ToolObservation(cached.success(), arguments, cached.result(), "CACHED");
        }
        if (stoppedReason != null || searches >= maxSearches) {
            String reason = stoppedReason != null ? stoppedReason : "本次任务已达到知识库实际检索上限（" + maxSearches + "次）";
            return new ReactToolRegistry.ToolObservation(false, arguments,
                    reason + "；请停止检索，基于已有证据回答，证据不足时明确说明。", "BLOCKED");
        }
        searches++;
        ReactToolRegistry.ToolObservation result = operation.get();
        if (result.success()) {
            results.put(key, result);
        } else if ("NON_RETRYABLE".equals(result.execution())) {
            stoppedReason = result.result();
        }
        return result;
    }

    /** @return 是否仍允许向模型提供检索工具；缓存结果仍可经服务端复用 */
    public synchronized boolean available() {
        return enabled && stoppedReason == null && searches < maxSearches;
    }

    /** @return 本次用户请求是否明确授权检索 */
    public boolean enabled() {
        return enabled;
    }

    /** @return 模型使用的当前用户选择，历史消息不能覆盖 */
    public String selectionInstruction() {
        String knowledge = enabled ? "用户已勾选知识库，允许按需检索当前用户的文档。"
                : "用户未勾选知识库，本次任务禁止知识库检索，也不得分配KNOWLEDGE角色；需要检索时请提示先勾选知识库。";
        return knowledge + (networkEnabled ? "用户已开启联网，允许按需搜索和获取公开网页。"
                : "用户未开启联网，禁止调用web_search和http_get，也不得分配NETWORK角色；需要联网时提示先开启联网。");
    }

    /** @return 本次用户是否授权内置网页搜索和网页读取 */
    public boolean networkEnabled() {
        return networkEnabled;
    }

    /** @return 工具是否受未开启的联网开关限制 */
    public boolean blocksNetworkTool(String name) {
        return !networkEnabled && ("web_search".equals(name) || "http_get".equals(name));
    }
}
