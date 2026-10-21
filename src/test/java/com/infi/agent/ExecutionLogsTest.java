package com.infi.agent;

import com.infi.agent.common.ExecutionLogs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionLogsTest {
    @Test
    void scopesRestoreContextEvenAfterFailureAndGenerateUniqueIds() {
        var previous = MDC.getCopyOfContextMap();
        MDC.put("runId", "caller");
        MDC.put("traceId", "upstream");
        try {
            String first = ExecutionLogs.run(LoggerFactory.getLogger(getClass()), 7L, "React", () -> {
                assertEquals("7", MDC.get("userId"));
                assertEquals("React", MDC.get("mode"));
                assertEquals("upstream", MDC.get("traceId"));
                return ExecutionLogs.runId();
            });
            assertNotEquals("caller", first);
            assertEquals("caller", MDC.get("runId"));
            assertNull(MDC.get("mode"));
            assertThrows(IllegalStateException.class, () ->
                    ExecutionLogs.run(LoggerFactory.getLogger(getClass()), 8L, "MultiAgent", () -> {
                        assertNotEquals(first, ExecutionLogs.runId());
                        throw new IllegalStateException("test failure");
                    }));
            assertEquals("caller", MDC.get("runId"));
            assertNull(MDC.get("userId"));
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
