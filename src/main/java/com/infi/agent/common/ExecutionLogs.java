package com.infi.agent.common;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.MDC;

/** 为一次执行建立可跨工具线程传递的日志上下文，退出后恢复调用者上下文。 */
public final class ExecutionLogs {
    private ExecutionLogs() {}

    public static String runId() {
        String id = MDC.get("runId");
        return id == null ? UUID.randomUUID().toString() : id;
    }

    public static <T> T run(Logger log, Long userId, String mode, Supplier<T> operation) {
        var previous = MDC.getCopyOfContextMap();
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        MDC.put("userId", String.valueOf(userId));
        MDC.put("mode", mode);
        long started = System.nanoTime();
        log.info("Task started, runId={}, userId={}, mode={}", runId, userId, mode);
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            log.error("Task aborted, runId={}, userId={}, mode={}", runId, userId, mode, exception);
            throw exception;
        } finally {
            log.info("Task ended, runId={}, mode={}, durationMs={}", runId, mode,
                    java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
