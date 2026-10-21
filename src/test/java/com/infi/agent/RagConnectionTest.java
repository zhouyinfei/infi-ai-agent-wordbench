package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.RagProperties;
import com.infi.agent.rag.RagConnectionException;
import com.infi.agent.rag.RagInfrastructure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证认证协议、证书加载及可公开的错误信息，不连接外部服务。 */
class RagConnectionTest {
    private HttpServer server;
    private RagProperties properties;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private int status = 200;
    private String responseBody = "{}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties = new RagProperties();
        properties.setElasticsearchUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void basicAuthenticationUsesConfiguredUsernameAndPassword() throws Exception {
        properties.setElasticsearchUsername("elastic");
        properties.setElasticsearchPassword("test-only-password");
        new RagInfrastructure(properties, new ObjectMapper()).elastic("POST", "/_search", Map.of());
        assertThat(authorization.get()).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString("elastic:test-only-password".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void apiKeyTakesPrecedenceOverBasicAuthentication() throws Exception {
        properties.setElasticsearchApiKey("test-api-key");
        properties.setElasticsearchUsername("elastic");
        properties.setElasticsearchPassword("test-only-password");
        new RagInfrastructure(properties, new ObjectMapper()).elastic("POST", "/_search", Map.of());
        assertThat(authorization.get()).isEqualTo("ApiKey test-api-key");
    }

    @Test
    void authenticationFailureIsActionableWithoutLeakingCredentials() {
        status = 401;
        properties.setElasticsearchApiKey("private-test-value");
        assertThatThrownBy(() -> new RagInfrastructure(properties, new ObjectMapper())
                .elastic("POST", "/_search", Map.of())).isInstanceOf(RagConnectionException.class)
                .hasMessageContaining("HTTP 401").hasMessageNotContaining("private-test-value");
    }

    @Test
    void unreadableCaCertificateFailsClosed() {
        properties.setElasticsearchCaCertificate(".tools/does-not-exist.crt");
        assertThatThrownBy(() -> new RagInfrastructure(properties, new ObjectMapper())
                .elastic("POST", "/_search", Map.of())).isInstanceOf(RagConnectionException.class)
                .hasMessageContaining("CA 证书加载失败");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void embeddingQuotaFailureExplainsCauseWithoutExposingUpstreamBody() {
        status = 403;
        responseBody = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private upstream details\"}}";
        properties.setEmbeddingUrl(properties.getElasticsearchUrl() + "/v1");
        properties.setEmbeddingApiKey("private-test-key");
        assertThatThrownBy(() -> new RagInfrastructure(properties, new ObjectMapper()).embed("测试"))
                .isInstanceOf(RagConnectionException.class).hasMessageContaining("额度不足")
                .hasMessageNotContaining("private upstream details").hasMessageNotContaining("private-test-key")
                .satisfies(error -> assertThat(((RagConnectionException) error).isRetryable()).isFalse());
        assertThat(authorization.get()).isEqualTo("Bearer private-test-key");
    }

    @Test
    void nonJsonEmbeddingFailureStillReportsServiceAndStatus() {
        status = 502;
        responseBody = "upstream unavailable";
        properties.setEmbeddingUrl(properties.getElasticsearchUrl() + "/v1");
        assertThatThrownBy(() -> new RagInfrastructure(properties, new ObjectMapper()).embed("测试"))
                .isInstanceOf(RagConnectionException.class).hasMessageContaining("Embedding")
                .hasMessageContaining("HTTP 502");
    }

    @Test
    void embeddingDimensionMismatchReportsExpectedAndActualDimensions() {
        responseBody = "{\"data\":[{\"embedding\":[1.0,0.5]}]}";
        properties.setEmbeddingUrl(properties.getElasticsearchUrl() + "/v1");
        properties.setDimensions(1024);
        assertThatThrownBy(() -> new RagInfrastructure(properties, new ObjectMapper()).embed("测试"))
                .isInstanceOf(RagConnectionException.class).hasMessageContaining("配置 1024")
                .hasMessageContaining("返回 2");
    }
}
