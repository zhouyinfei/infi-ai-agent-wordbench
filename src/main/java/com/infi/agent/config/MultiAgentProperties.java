package com.infi.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 整次协作的预算，不能通过客户端或模型覆盖。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.multi-agent")
public class MultiAgentProperties {
    @Min(3)
    @Max(30)
    private int maxIterations = 12;
    @Min(1)
    @Max(40)
    private int maxToolCalls = 10;
    @Min(1)
    @Max(5)
    private int maxRepeatedCalls = 2;
    @Min(1)
    @Max(4)
    private int maxTasks = 4;
    private Duration taskTimeout = Duration.ofSeconds(90);
}
