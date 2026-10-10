package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** 注解提供模型工具描述，JSON适配入口保留严格校验及十进制精度。 */
public interface ReactTool {
    default ToolSpecification specification() {
        List<Method> methods = Arrays.stream(getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class)).toList();
        if (methods.size() != 1) {
            throw new IllegalStateException("每个ReAct工具须声明一个@Tool方法");
        }
        return ToolSpecifications.toolSpecificationFrom(methods.get(0));
    }

    String execute(JsonNode arguments);
}
