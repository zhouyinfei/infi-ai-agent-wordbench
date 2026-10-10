package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 获取真实服务器时刻，未指定时区时采用 Asia/Shanghai。 */
@Component
@RequiredArgsConstructor
public class CurrentTimeTool implements ReactTool {
    private final Clock clock;

    /**
     * 校验时间工具仅接受可选的字符串 timezone 参数，未指定时采用 Asia/Shanghai。
     *
     * @param arguments 空 JSON 对象或包含 timezone 的 JSON 对象
     * @return 指定时区的当前日期时间
     * @throws IllegalArgumentException 参数结构、时区类型、长度或名称不合法
     */
    @Override
    public String execute(JsonNode arguments) {
        if (!arguments.isObject() || arguments.size() > 1
                || (arguments.size() == 1 && !arguments.has("timezone"))
                || (arguments.has("timezone") && !arguments.get("timezone").isTextual())) {
            throw new IllegalArgumentException("时间工具仅接受字符串 timezone 参数");
        }
        return currentTime(arguments.path("timezone").asText("Asia/Shanghai"));
    }

    /**
     * 读取注入时钟的当前时刻并转换到指定时区，返回 ISO_ZONED_DATE_TIME 格式；注解用于生成工具协议。
     *
     * @param timezone IANA 时区名称，为 null 时默认 Asia/Shanghai
     * @return 包含时区信息的当前日期时间字符串
     * @throws IllegalArgumentException 时区名称过长或无法识别
     */
    @Tool(name = "current_time", value = "获取指定时区的当前日期时间")
    public String currentTime(@P(name = "timezone", value = "IANA时区，默认Asia/Shanghai",
            required = false) String timezone) {
        if (timezone == null) {
            timezone = "Asia/Shanghai";
        }
        if (timezone.length() > 64) {
            throw new IllegalArgumentException("时区名称过长");
        }
        try {
            return ZonedDateTime.ofInstant(clock.instant(), ZoneId.of(timezone))
                    .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("时区不合法，请使用例如 Asia/Shanghai 或 UTC");
        }
    }
}
