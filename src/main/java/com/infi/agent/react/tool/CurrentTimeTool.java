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

    @Override
    public String execute(JsonNode arguments) {
        if (!arguments.isObject() || arguments.size() > 1
                || (arguments.size() == 1 && !arguments.has("timezone"))
                || (arguments.has("timezone") && !arguments.get("timezone").isTextual())) {
            throw new IllegalArgumentException("时间工具仅接受字符串 timezone 参数");
        }
        return currentTime(arguments.path("timezone").asText("Asia/Shanghai"));
    }

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
