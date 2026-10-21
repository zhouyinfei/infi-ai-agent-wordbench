package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.infi.agent.react.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class ParallelToolExecutionTest {
    private ReactTool tool(String name, Function<JsonNode, String> operation) {
        return new ReactTool() {
            public ToolSpecification specification() { return ToolSpecification.builder().name(name).description(name).build(); }
            public String execute(JsonNode arguments) { return operation.apply(arguments); }
        };
    }

    private ToolExecutionRequest call(String name, String id) {
        return ToolExecutionRequest.builder().name(name).id(id).arguments("{\"id\":\"" + id + "\"}").build();
    }

    @Test
    void overlapsReadCallsPreservesOrderAndPropagatesIdentity() {
        var pool = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(2);
        var authentication = new UsernamePasswordAuthenticationToken("alice", null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var registry = new ReactToolRegistry(List.of(tool("calculator", arguments -> {
                assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
                latch.countDown();
                try { assertTrue(latch.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return arguments.get("id").asText();
            })), new ObjectMapper());
            registry.setBatchExecutor(pool);
            var results = registry.executeBatch(List.of(call("calculator", "first"), call("calculator", "second")),
                    registry.newKnowledgeScope(), Duration.ofSeconds(3));
            assertEquals(List.of("first", "second"), results.stream().map(r -> r.observation().result()).toList());
            assertNull(pool.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get());
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        } finally {
            SecurityContextHolder.clearContext();
            pool.shutdownNow();
        }
    }

    @Test
    void mutationsAreBarriersBetweenReadGroups() {
        var pool = Executors.newFixedThreadPool(2);
        var events = new CopyOnWriteArrayList<String>();
        try {
            var registry = new ReactToolRegistry(List.of(
                    tool("file_read", a -> { events.add(a.get("id").asText()); return "ok"; }),
                    tool("file_write", a -> { events.add("write"); return "ok"; })), new ObjectMapper());
            registry.setBatchExecutor(pool);
            registry.executeBatch(List.of(call("file_read", "before"), call("file_write", "write"),
                    call("file_read", "after")), registry.newKnowledgeScope(), Duration.ofSeconds(2));
            assertEquals(List.of("before", "write", "after"), events);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void deadlineInterruptsRunningTools() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var interrupted = new CountDownLatch(1);
        try {
            var registry = new ReactToolRegistry(List.of(tool("calculator", a -> {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "stopped";
            })), new ObjectMapper());
            registry.setBatchExecutor(pool);
            var error = assertThrows(ReactToolRegistry.BatchExecutionException.class,
                    () -> registry.executeBatch(List.of(call("calculator", "1")), registry.newKnowledgeScope(),
                            Duration.ofMillis(200)));
            assertEquals("TIMEOUT", error.status());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
