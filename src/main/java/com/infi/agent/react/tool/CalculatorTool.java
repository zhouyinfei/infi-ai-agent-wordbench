package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import java.math.BigDecimal;
import java.math.MathContext;
import org.springframework.stereotype.Component;

/** 有界十进制四则运算，不解释或执行任何表达式代码。 */
@Component
public class CalculatorTool implements ReactTool {
    private static final int MAX_ARGUMENT_LENGTH = 100;
    private static final BigDecimal MAX_ABSOLUTE_VALUE = new BigDecimal("1E20");

    @Override
    public String execute(JsonNode arguments) {
        if (!arguments.isObject() || arguments.size() != 3 || !arguments.path("operator").isTextual()) {
            throw new IllegalArgumentException("计算器参数须包含 left、operator、right");
        }
        BigDecimal left = number(arguments.get("left"));
        BigDecimal right = number(arguments.get("right"));
        Operator operator;
        try {
            operator = Operator.valueOf(arguments.path("operator").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的运算符");
        }
        return calculate(left, operator, right);
    }

    @Tool(name = "calculator", value = "精确计算两个数的加减乘除。复合计算请分多次调用，使用上次结果继续计算。")
    public String calculate(
            @P(name = "left", value = "左操作数，绝对值不超过1E20") BigDecimal left,
            @P(name = "operator", value = "运算符") Operator operator,
            @P(name = "right", value = "右操作数，绝对值不超过1E20") BigDecimal right) {
        BigDecimal result = switch (operator) {
            case ADD -> left.add(right);
            case SUBTRACT -> left.subtract(right);
            case MULTIPLY -> left.multiply(right);
            case DIVIDE -> {
                if (right.signum() == 0) {
                    throw new IllegalArgumentException("除数不能为零");
                }
                yield left.divide(right, MathContext.DECIMAL128);
            }
        };
        return result.stripTrailingZeros().toPlainString();
    }

    public enum Operator {
        ADD, SUBTRACT, MULTIPLY, DIVIDE
    }

    private BigDecimal number(JsonNode node) {
        if (node == null || !node.isNumber() || node.asText().length() > MAX_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException("操作数须为合法数字");
        }
        BigDecimal value = node.decimalValue();
        if (Math.abs((long) value.scale()) > MAX_ARGUMENT_LENGTH
                || value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0) {
            throw new IllegalArgumentException("操作数超出允许范围");
        }
        return value;
    }
}
