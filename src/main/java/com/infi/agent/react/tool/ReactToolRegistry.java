package com.infi.agent.react.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.vo.ReactToolVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 统一白名单及参数解析；调用失败转换为可反馈给模型的观察。 */
@Component
public class ReactToolRegistry {
    private static final int MAX_ARGUMENT_LENGTH = 2000;
    private final Map<String, ReactTool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

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
        if (!tools.containsKey(request.name())) {
            return new ToolObservation(false, null, "工具不在服务端注册白名单中");
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
            return new ToolObservation(true, arguments, tools.get(request.name()).execute(arguments));
        } catch (JsonProcessingException exception) {
            return new ToolObservation(false, null, "工具参数不是合法JSON");
        } catch (IllegalArgumentException exception) {
            return new ToolObservation(false, arguments, exception.getMessage());
        } catch (RuntimeException exception) {
            return new ToolObservation(false, arguments, "工具执行异常");
        }
    }

    public record ToolObservation(boolean success, JsonNode arguments, String result) {
    }
}
