package com.infi.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** ReAct 执行预算，客户端不可覆盖。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.react")
public class ReactProperties {
    @Min(1)
    @Max(20)
    private int maxIterations = 6;
    @Min(1)
    @Max(40)
    private int maxToolCalls = 10;
    @Min(1)
    @Max(5)
    private int maxRepeatedCalls = 2;
    private Duration taskTimeout = Duration.ofSeconds(90);
}
