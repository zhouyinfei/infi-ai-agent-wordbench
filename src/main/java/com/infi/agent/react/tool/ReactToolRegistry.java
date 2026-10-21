package com.infi.agent.react.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.mcp.McpToolCatalog;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.RagProperties;
import com.infi.agent.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import com.infi.agent.vo.ReactToolVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 统一白名单及参数解析；调用失败转换为可反馈给模型的观察。 */
@Component
public class ReactToolRegistry {
    private static final int MAX_ARGUMENT_LENGTH = 400_000;
    private final Map<String, ReactTool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private java.util.concurrent.Executor batchExecutor = java.util.concurrent.ForkJoinPool.commonPool();

    @Autowired
    public void setBatchExecutor(@org.springframework.beans.factory.annotation.Qualifier("toolTaskExecutor")
                                java.util.concurrent.Executor executor) {
        this.batchExecutor = executor;
    }

    /** 连续的只读工具可并行；文件修改和未知 MCP 工具构成顺序屏障。结果保持请求顺序。 */
    public List<TimedObservation> executeBatch(List<ToolExecutionRequest> calls, KnowledgeSearchScope scope,
                                             java.time.Duration remaining) {
        long deadline = System.nanoTime() + remaining.toNanos();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        List<TimedObservation> results = new java.util.ArrayList<>();
        List<java.util.concurrent.Future<TimedObservation>> pending = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < calls.size();) {
                int end = i + 1;
                if (parallelSafe(calls.get(i).name())) {
                    while (end < calls.size() && parallelSafe(calls.get(end).name())) end++;
                }
                pending.clear();
                for (int j = i; j < end; j++) {
                    if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
                    ToolExecutionRequest call = calls.get(j);
                    var task = new java.util.concurrent.FutureTask<TimedObservation>(() -> {
                        var previous = SecurityContextHolder.getContext();
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        SecurityContextHolder.setContext(context);
                        long started = System.nanoTime();
                        try {
                            return new TimedObservation(execute(call, scope),
                                    java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
                        } finally {
                            SecurityContextHolder.setContext(previous);
                        }
                    });
                    pending.add(task);
                    batchExecutor.execute(task);
                }
                for (var future : pending) {
                    results.add(future.get(Math.max(1, deadline - System.nanoTime()),
                            java.util.concurrent.TimeUnit.NANOSECONDS));
                }
                i = end;
            }
            return List.copyOf(results);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BatchExecutionException("FAILED", "工具执行已中断");
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new BatchExecutionException("TIMEOUT", "任务超过执行时限");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.RejectedExecutionException exception) {
            throw new BatchExecutionException("FAILED", "工具执行失败或执行队列已满");
        } finally {
            pending.forEach(future -> { if (!future.isDone()) future.cancel(true); });
        }
    }

    private boolean parallelSafe(String name) {
        return java.util.Set.of("calculator", "current_time", "web_search", "http_get", "file_read", "file_list")
                .contains(name);
    }

    public record TimedObservation(ToolObservation observation, long durationMs) {}

    public static class BatchExecutionException extends RuntimeException {
        private final String status;
        public BatchExecutionException(String status, String message) {
            super(message);
            this.status = status;
        }
        public String status() { return status; }
    }
    private int maxKnowledgeSearches = KnowledgeSearchScope.DEFAULT_MAX_SEARCHES;

    /**
     * 合并内置工具与已授权的 MCP 工具，沿用统一白名单和参数校验。
     *
     * @param toolList 内置工具
     * @param objectMapper 参数解析器
     * @param catalog MCP 工具目录
     */
    public ReactToolRegistry(List<ReactTool> toolList, ObjectMapper objectMapper, McpToolCatalog catalog) {
        this(Stream.concat(toolList.stream(), catalog.tools().stream()).toList(), objectMapper);
    }

    /** 注入检索预算；测试和独立注册表沿用默认上限。 */
    @Autowired
    public ReactToolRegistry(List<ReactTool> toolList, ObjectMapper objectMapper, McpToolCatalog catalog,
                             RagProperties properties) {
        this(toolList, objectMapper, catalog);
        maxKnowledgeSearches = properties.getMaxSearchesPerRun();
        new KnowledgeSearchScope(maxKnowledgeSearches);
    }

    /** @return 每次任务必须新建的缓存及检索预算 */
    public KnowledgeSearchScope newKnowledgeScope() {
        return new KnowledgeSearchScope(maxKnowledgeSearches);
    }

    /** @return 绑定本次用户授权的独立检索上下文 */
    public KnowledgeSearchScope newKnowledgeScope(boolean enabled) {
        return new KnowledgeSearchScope(maxKnowledgeSearches, enabled);
    }

    /** @return 绑定本次知识库和联网独立授权的任务上下文 */
    public KnowledgeSearchScope newKnowledgeScope(boolean enabled, boolean networkEnabled) {
        return new KnowledgeSearchScope(maxKnowledgeSearches, enabled, networkEnabled);
    }

    /** 达到检索预算或不可重试错误后，不再向模型提供检索工具。 */
    public List<ToolSpecification> specifications(KnowledgeSearchScope scope) {
        return specifications().stream().filter(tool -> !scope.blocksNetworkTool(tool.name()))
                .filter(tool -> scope.available() || !KnowledgeSearchScope.TOOL_NAME.equals(tool.name())).toList();
    }

    /**
     * 注册 Spring 提供的工具实例，按注解生成的工具名称建立服务端白名单。
     *
     * @param toolList 交由 Spring 管理的工具实例
     * @param objectMapper 用于严格解析工具 JSON 参数的 Jackson 解析器
     * @throws IllegalStateException 工具名称重复，或工具注解声明不合法
     */
    public ReactToolRegistry(List<ReactTool> toolList, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (ReactTool tool : toolList) {
            if (tools.putIfAbsent(tool.specification().name(), tool) != null) {
                throw new IllegalStateException("工具名称重复");
            }
        }
    }

    /**
     * 查询服务端已注册的工具名称。
     *
     * @return 按注册顺序排列的不可修改名称列表
     */
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 将工具名称和说明转换为工作台配置使用的展示对象。
     *
     * @return 全部已注册工具的展示描述
     */
    public List<ReactToolVO> descriptions() {
        return tools.values().stream().map(tool -> new ReactToolVO(tool.specification().name(),
                tool.specification().description())).toList();
    }

    /**
     * 生成全部已注册工具的模型协议，供 LLM 根据任务自动选择调用。
     *
     * @return 工具名称、说明及参数结构列表
     */
    public List<ToolSpecification> specifications() {
        return tools.values().stream().map(ReactTool::specification).toList();
    }

    /**
     * 拒绝未注册工具，严格解析有长度限制的 JSON 参数并调用工具；将参数错误和执行异常转换为观察结果。
     *
     * @param request 模型返回的工具名称、调用 ID 和 JSON 参数
     * @return 包含成功标志、解析参数及结果或错误说明的观察对象
     */
    public ToolObservation execute(ToolExecutionRequest request) {
        return execute(request, newKnowledgeScope());
    }

    /** 使用调用方持有的任务级上下文，控制跨步骤的真实检索次数。 */
    public ToolObservation execute(ToolExecutionRequest request, KnowledgeSearchScope scope) {
        if (!tools.containsKey(request.name())) {
            return new ToolObservation(false, null, "工具不在服务端注册白名单中");
        }
        if (scope.blocksNetworkTool(request.name())) {
            return new ToolObservation(false, null, "用户未开启联网，本次任务禁止网页搜索和网页读取。", "NETWORK_BLOCKED");
        }
        JsonNode arguments = null;
        try {
            if (request.arguments() == null || request.arguments().length() > MAX_ARGUMENT_LENGTH) {
                throw new IllegalArgumentException("工具参数为空或过长");
            }
            arguments = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(request.arguments());
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("工具参数须为JSON对象");
            }
            if (KnowledgeSearchScope.TOOL_NAME.equals(request.name())) {
                ToolArguments.fields(arguments, "query");
                String query = ToolArguments.text(arguments, "query");
                if (query.isBlank() || query.length() > 2000) {
                    throw new IllegalArgumentException("检索问题须为 1–2000 字符");
                }
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                String owner = authentication != null && authentication.getPrincipal() instanceof UserPrincipal user
                        ? user.id().toString() : "anonymous";
                JsonNode parsed = arguments;
                return scope.execute(owner, query, arguments, () -> invoke(request.name(), parsed));
            }
            return invoke(request.name(), arguments);
        } catch (JsonProcessingException exception) {
            return new ToolObservation(false, null, "工具参数不是合法JSON");
        } catch (IllegalArgumentException exception) {
            return new ToolObservation(false, arguments, exception.getMessage());
        } catch (RuntimeException exception) {
            return new ToolObservation(false, arguments, "工具执行异常");
        }
    }

    private ToolObservation invoke(String name, JsonNode arguments) {
        try {
            return new ToolObservation(true, arguments, tools.get(name).execute(arguments));
        } catch (BusinessException exception) {
            if (!KnowledgeSearchScope.TOOL_NAME.equals(name)) {
                return new ToolObservation(false, arguments, "工具执行异常");
            }
            boolean terminal = "RAG_NON_RETRYABLE".equals(exception.getCode()) || "RAG_DISABLED".equals(exception.getCode());
            return new ToolObservation(false, arguments, exception.getMessage(), terminal ? "NON_RETRYABLE" : "EXECUTED");
        } catch (IllegalArgumentException exception) {
            return new ToolObservation(false, arguments, exception.getMessage());
        } catch (RuntimeException exception) {
            return new ToolObservation(false, arguments, "工具执行异常");
        }
    }

    public record ToolObservation(boolean success, JsonNode arguments, String result, String execution) {
        public ToolObservation(boolean success, JsonNode arguments, String result) {
            this(success, arguments, result, "EXECUTED");
        }

        /** @return 区分模型提出调用、实际检索、缓存复用和已阻止的重试 */
        public String summary() {
            return switch (execution) {
                case "CACHED" -> "复用本次任务知识库检索结果";
                case "BLOCKED" -> "知识库检索已停止，未再次请求服务";
                case "NETWORK_BLOCKED" -> "联网未开启，已拦截联网工具调用";
                case "NON_RETRYABLE" -> "知识库检索失败，本次任务停止重试";
                default -> success ? "工具执行完成" : "工具调用失败";
            };
        }
    }
}
