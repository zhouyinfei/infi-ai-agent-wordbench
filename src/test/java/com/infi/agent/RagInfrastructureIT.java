package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.RagProperties;
import com.infi.agent.rag.DocumentParser;
import com.infi.agent.rag.RagInfrastructure;
import com.infi.agent.service.impl.KnowledgeServiceImpl;
import com.sun.net.httpserver.HttpServer;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

/** 真实 MinIO / ES 与本地 Embedding 协议替身联调，独立命名并清理测试资源。 */
@EnabledIfEnvironmentVariable(named = "RAG_INFRASTRUCTURE_TEST", matches = "true")
class RagInfrastructureIT {
    @Test
    void uploadSearchDownloadIsolationAndDelete() throws Exception {
        HttpServer embedding = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embedding.createContext("/embeddings", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(new ObjectMapper().readTree(exchange.getRequestBody()).path("input").asText()).isNotBlank();
            byte[] body = "{\"data\":[{\"embedding\":[1.0,0.5,0.2]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        embedding.start();
        boolean useLocal = "true".equals(System.getenv("RAG_IT_USE_LOCAL"));
        var localEnvironment = new org.springframework.core.env.StandardEnvironment();
        if (useLocal) {
            var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
            for (String name : new String[]{"application.yml", "application-local.yml"}) {
                for (var source : loader.load(name, new org.springframework.core.io.FileSystemResource("src/main/resources/" + name))) {
                    localEnvironment.getPropertySources().addFirst(source);
                }
            }
        }
        RagProperties properties = useLocal ? org.springframework.boot.context.properties.bind.Binder.get(localEnvironment)
                .bind("app.rag", org.springframework.boot.context.properties.bind.Bindable.of(RagProperties.class)).get()
                : new RagProperties();
        properties.setEnabled(true);
        if (!useLocal) {
        properties.setMinioEndpoint(System.getenv().getOrDefault("RAG_IT_MINIO_URL", "http://localhost:9000"));
        properties.setElasticsearchUrl(System.getenv().getOrDefault("RAG_IT_ES_URL", "http://localhost:9200"));
        properties.setMinioAccessKey(System.getenv("RAG_IT_MINIO_KEY"));
        properties.setMinioSecretKey(System.getenv("RAG_IT_MINIO_SECRET"));
        properties.setElasticsearchApiKey(System.getenv().getOrDefault("RAG_IT_ES_KEY", ""));
        }
        String suffix = System.getenv().getOrDefault("RAG_IT_SUFFIX", UUID.randomUUID().toString());
        if (!suffix.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("测试资源后缀须为 UUID");
        }
        properties.setBucket("rag-it-" + suffix);
        properties.setIndex("rag-it-" + suffix);
        properties.setDimensions(3);
        properties.setEmbeddingUrl("http://127.0.0.1:" + embedding.getAddress().getPort());
        properties.setEmbeddingApiKey("");
        RagInfrastructure infrastructure = new RagInfrastructure(properties, new ObjectMapper());
        var dataSource = useLocal ? new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                localEnvironment.getProperty("spring.datasource.url"), localEnvironment.getProperty("spring.datasource.username"),
                localEnvironment.getProperty("spring.datasource.password"))
                : new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("org.h2.Driver",
                    "jdbc:h2:mem:rag" + suffix + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        if (!useLocal) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("schema.sql")).execute(dataSource);
        }
        var session = RagTestDatabase.open(dataSource);
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(properties, infrastructure, new DocumentParser(),
                session.getMapper(com.infi.agent.mapper.KnowledgeDocumentMapper.class));
        String id = null;
        try {
            byte[] original = "产品手册：支持知识库检索、资料溯源和智能问答。".getBytes(StandardCharsets.UTF_8);
            var document = service.upload(99101L, new MockMultipartFile("file", "产品手册.md", "text/markdown", original));
            id = document.id();
            assertThat(document.status()).isEqualTo("READY");
            assertThat(service.list(99101L)).hasSize(1);
            assertThat(service.search(99101L, "产品功能", 5)).singleElement().satisfies(hit -> {
                assertThat(hit.filename()).isEqualTo("产品手册.md");
                assertThat(hit.text()).contains("智能问答");
                assertThat(hit.score()).isCloseTo(1.0, within(0.001));
            });
            assertThat(service.download(99101L, id)).isEqualTo(original);
            assertThat(service.list(99102L)).isEmpty();
            assertThat(service.search(99102L, "产品功能", 5)).isEmpty();
            String finalId = id;
            assertThatThrownBy(() -> service.download(99102L, finalId)).hasMessage("文档不存在");
            service.delete(99101L, id);
            id = null;
            assertThat(service.list(99101L)).isEmpty();
            assertThat(service.search(99101L, "产品功能", 5)).isEmpty();
        } finally {
            try {
                if (id != null) {
                    service.delete(99101L, id);
                }
                infrastructure.elastic("DELETE", "", null);
                MinioClient.builder().endpoint(properties.getMinioEndpoint())
                        .credentials(properties.getMinioAccessKey(), properties.getMinioSecretKey()).build()
                        .removeBucket(RemoveBucketArgs.builder().bucket(properties.getBucket()).build());
                if (useLocal) {
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                            "DELETE FROM ai_knowledge_document WHERE user_id = ? AND vector_index = ?", 99101L, properties.getIndex());
                }
            } finally {
                session.close();
                embedding.stop(0);
            }
        }
    }
}
