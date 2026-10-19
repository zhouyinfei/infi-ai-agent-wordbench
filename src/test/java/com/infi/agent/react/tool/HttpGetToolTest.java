package com.infi.agent.react.tool;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpGetToolTest {
    @Test void fetchesTextAndRejectsRedirectsBinaryAndLargeResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.equals("/large") ? "x".repeat(65_537) : "你好").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.equals("/binary")
                    ? "application/octet-stream" : "application/json; charset=utf-8");
            if (path.equals("/redirect")) exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(path.equals("/redirect") ? 302 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            // 只为传输测试绕过生产 URL 策略；生产策略由 ExtendedToolsTest 单独验证。
            HttpGetTool tool = new HttpGetTool("") {
                @Override URI validate(String url) { return URI.create(url); }
            };
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(tool.get(base + "/")).contains("HTTP 200", "你好", "不可信外部内容");
            assertThatThrownBy(() -> tool.get(base + "/redirect")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("302");
            assertThatThrownBy(() -> tool.get(base + "/binary")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("仅支持");
            assertThatThrownBy(() -> tool.get(base + "/large")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("64 KiB");
        } finally {
            server.stop(0);
        }
    }
}
