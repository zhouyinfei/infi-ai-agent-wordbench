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

    public ReactToolRegistry(List<ReactTool> toolList, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (ReactTool tool : toolList) {
            if (tools.putIfAbsent(tool.specification().name(), tool) != null) {
                throw new IllegalStateException("工具名称重复");
            }
        }
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public List<ReactToolVO> descriptions() {
        return tools.values().stream().map(tool -> new ReactToolVO(tool.specification().name(),
                tool.specification().description())).toList();
    }

    public List<ToolSpecification> specifications(List<String> allowedTools) {
        return allowedTools.stream().map(name -> tools.get(name).specification()).toList();
    }

    public ToolObservation execute(ToolExecutionRequest request, List<String> allowedTools) {
        if (!allowedTools.contains(request.name()) || !tools.containsKey(request.name())) {
            return new ToolObservation(false, null, "工具不在本次授权白名单中");
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
