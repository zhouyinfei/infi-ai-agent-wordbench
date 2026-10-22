package com.infi.agent;

import com.infi.agent.config.AiModelProperties;
import com.infi.agent.react.model.*;
import com.infi.agent.common.BusinessException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelCatalogTest {
    private AiModelProperties model(String name) {
        var model = new AiModelProperties();
        model.setEnabled(true); model.setApiKey("test-key"); model.setModelName(name);
        return model;
    }
    @Test void legacyAndNamedDefaultsAndExplicitSelection() {
        var root = model("legacy");
        assertThat(ModelCatalog.resolve(root, null)).isSameAs(root);
        var second = model("second"); root.getModels().put("second", second);
        root.setDefaultModel("second");
        assertThat(ModelCatalog.resolve(root, null)).isSameAs(second);
        assertThat(ModelCatalog.resolve(root, "second")).isSameAs(second);
        assertThatThrownBy(() -> ModelCatalog.resolve(root, "missing")).isInstanceOf(BusinessException.class);
        second.setEnabled(false);
        assertThatThrownBy(() -> ModelCatalog.resolve(root, "second")).isInstanceOf(BusinessException.class);
    }
    @Test void selectionIsIsolatedAndRestoredEvenAfterFailure() {
        assertThat(ModelSelection.current()).isNull();
        try (var outer = ModelSelection.select("first")) {
            assertThat(CompletableFuture.supplyAsync(ModelSelection::current).join()).isNull();
            assertThatThrownBy(() -> {
                try (var inner = ModelSelection.select("second")) {
                    assertThat(ModelSelection.current()).isEqualTo("second");
                    throw new IllegalStateException();
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(ModelSelection.current()).isEqualTo("first");
        }
        assertThat(ModelSelection.current()).isNull();
    }
    @Test void unknownProtocolAndInvalidTimeoutAreUnavailable() {
        var model = model("test");
        model.setProvider("unsupported"); assertThat(ModelCatalog.configured(model)).isFalse();
        model.setProvider("openai-compatible"); model.setRequestTimeout(java.time.Duration.ZERO);
        assertThat(ModelCatalog.configured(model)).isFalse();
    }
    @Test void runtimeSelectionChangesActualRequestAndRestoresDefault() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var names = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext("/v1/chat/completions", exchange -> {
            names.add(json.readTree(exchange.getRequestBody()).get("model").asText());
            byte[] body = "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var root = model("first"); var second = model("second");
            root.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            second.setBaseUrl(root.getBaseUrl()); root.getModels().put("secondary", second);
            var gateway = new OpenAiReactModelGateway(root);
            var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(dev.langchain4j.data.message.UserMessage.from("hello")).build();
            try (var selection = ModelSelection.select("secondary")) {
                gateway.chat(request, java.time.Duration.ofSeconds(5));
            }
            gateway.chat(request, java.time.Duration.ofSeconds(5));
            assertThat(names).containsExactly("second", "first");
        } finally { server.stop(0); }
    }
}
