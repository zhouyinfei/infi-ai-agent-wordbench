package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** 工具 JSON 参数的公共严格校验。 */
final class ToolArguments {
    private ToolArguments() { }

    static void fields(JsonNode arguments, String... names) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("工具参数须为 JSON 对象");
        }
        Set<String> allowed = Set.of(names);
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("未知参数: " + name);
            }
        });
    }

    static String text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " 须为字符串");
        }
        return value.textValue();
    }
}
