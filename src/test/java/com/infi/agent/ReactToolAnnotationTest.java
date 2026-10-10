package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.CurrentTimeTool;
import com.infi.agent.react.tool.ReactTool;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证注解生成的模型协议仍与现有工具参数兼容。 */
class ReactToolAnnotationTest {
    @Test
    void calculatorAnnotationPreservesNameNumericArgumentsAndOperators() {
        var specification = new CalculatorTool().specification();
        assertThat(specification.name()).isEqualTo("calculator");
        assertThat(specification.parameters().required()).containsExactlyInAnyOrder("left", "operator", "right");
        assertThat(specification.parameters().properties().get("left")).isInstanceOf(JsonNumberSchema.class);
        assertThat(specification.parameters().properties().get("right")).isInstanceOf(JsonNumberSchema.class);
        var operator = (JsonEnumSchema) specification.parameters().properties().get("operator");
        assertThat(operator.enumValues()).containsExactly("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE");
    }

    @Test
    void timeAnnotationKeepsTimezoneOptional() {
        var specification = new CurrentTimeTool(Clock.systemUTC()).specification();
        assertThat(specification.name()).isEqualTo("current_time");
        assertThat(specification.parameters().properties()).containsOnlyKeys("timezone");
        assertThat(specification.parameters().required()).isNullOrEmpty();
    }

    @Test
    void unannotatedToolCannotBeRegistered() {
        ReactTool tool = new ReactTool() {
            @Override
            public String execute(JsonNode arguments) {
                return "unused";
            }
        };
        assertThatThrownBy(tool::specification).isInstanceOf(IllegalStateException.class);
    }
}
