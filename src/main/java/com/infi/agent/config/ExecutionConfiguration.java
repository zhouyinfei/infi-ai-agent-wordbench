package com.infi.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 并行工具调用使用有界线程池。 */
@Configuration
public class ExecutionConfiguration {
    /** @return 等待人工确认的任务使用独立有界线程池，不占用工具执行池 */
    @Bean
    public ThreadPoolTaskExecutor humanTaskExecutor() {
        return pool("agent-human-", 8, 16);
    }

    @Bean
    public ThreadPoolTaskExecutor toolTaskExecutor() {
        return pool("agent-tool-", 8, 64);
    }

    private ThreadPoolTaskExecutor pool(String prefix, int threads, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix(prefix);
        return executor;
    }
}
