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
    /**
     * 从工具实现类唯一的公开 @Tool 方法生成名称、描述及参数协议。
     *
     * @return 提供给模型的工具描述与参数结构
     * @throws IllegalStateException 公开 @Tool 方法数量不是一个
     */
    default ToolSpecification specification() {
        List<Method> methods = Arrays.stream(getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class)).toList();
        if (methods.size() != 1) {
            throw new IllegalStateException("每个ReAct工具须声明一个@Tool方法");
        }
        return ToolSpecifications.toolSpecificationFrom(methods.get(0));
    }

    /**
     * 作为 JSON 适配入口校验参数并调用具体工具，保留精度和边界检查。
     *
     * @param arguments 注册表已解析为对象的 JSON 参数
     * @return 反馈给模型的工具执行结果
     * @throws IllegalArgumentException 具体工具参数或业务约束不满足
     */
    String execute(JsonNode arguments);
}
