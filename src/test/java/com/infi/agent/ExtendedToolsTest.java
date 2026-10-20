package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.react.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExtendedToolsTest {
    @TempDir Path directory;

    private ReactToolRegistry registry() {
        ToolFileStore store = new ToolFileStore(directory.toString());
        return new ReactToolRegistry(List.of(new FileCreateTool(store), new FileReadTool(store),
                new FileWriteTool(store), new FileDeleteTool(store), new FileListTool(store),
                new HttpGetTool()), new ObjectMapper());
    }

    private ReactToolRegistry.ToolObservation call(ReactToolRegistry registry, String name, String json) {
        return registry.execute(ToolExecutionRequest.builder().name(name).arguments(json).build());
    }

    @Test void fileLifecycleThroughRegistry() throws Exception {
        var registry = registry();
        assertThat(registry.specifications()).hasSize(6);
        var created = call(registry, "file_create", "{\"path\":\"notes/a.txt\",\"content\":\"你好\"}");
        assertThat(created.success()).as(created.result()).isTrue();
        assertThat(call(registry, "file_create", "{\"path\":\"notes/a.txt\",\"content\":\"冲突\"}").success()).isFalse();
        assertThat(call(registry, "file_read", "{\"path\":\"notes/a.txt\"}").result()).isEqualTo("你好");
        String content = "a".repeat(60_000);
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of("path", "notes/a.txt", "content", content));
        assertThat(call(registry, "file_write", json).success()).isTrue();
        assertThat(call(registry, "file_read", "{\"path\":\"notes/a.txt\"}").result()).isEqualTo(content);
        assertThat(call(registry, "file_list", "{\"path\":\"notes\"}").result()).contains("a.txt");
        assertThat(call(registry, "file_delete", "{\"path\":\"notes/a.txt\"}").success()).isTrue();
        assertThat(directory.resolve("notes/a.txt")).doesNotExist();
    }

    @Test void rejectsEscapesDirectoriesOversizeAndUnknownArguments() throws Exception {
        var store = new ToolFileStore(directory.toString());
        assertThatThrownBy(() -> store.write("../escape.txt", "bad", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write("notes.txt:stream", "bad", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write(directory.resolve("absolute").toString(), "bad", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write("big.txt", "中".repeat(30_000), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write("missing.txt", "text", false)).isInstanceOf(IllegalArgumentException.class);
        Files.write(directory.resolve("big.txt"), new byte[65_537]);
        assertThatThrownBy(() -> store.read("big.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThat(call(registry(), "file_read", "{\"path\":\"a\",\"extra\":true}").success()).isFalse();
    }

    @Test void networkRejectsLocalAddressesAndInvalidUrls() {
        var registry = registry();
        for (String url : List.of("file:///etc/passwd", "http://localhost/", "http://127.0.0.1/",
                "http://10.0.0.1/", "http://192.168.1.1/", "http://localhost:8080/", "http://user:pass@localhost/")) {
            assertThat(call(registry, "http_get", "{\"url\":\"" + url + "\"}").success()).as(url).isFalse();
        }
    }
}
