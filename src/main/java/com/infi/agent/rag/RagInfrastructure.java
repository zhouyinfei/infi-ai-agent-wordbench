package com.infi.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.RagProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** MinIO 原文存储及 Elasticsearch REST 适配，连接采用有界超时。 */
@Component
@RequiredArgsConstructor
public class RagInfrastructure {
    private final RagProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private volatile boolean initialized;
    private volatile MinioClient minioClient;
    private volatile HttpClient elasticsearchClient;

    /** 延迟创建索引及私有桶，关闭 RAG 时不访问外部服务。 */
    public synchronized void initialize() throws Exception {
        if (initialized) {
            return;
        }
        if (!properties.getIndex().matches("[a-z0-9][a-z0-9_-]{0,199}")
                || properties.getDimensions() < 1 || properties.getDimensions() > 4096
                || properties.getTimeout() == null || properties.getTimeout().isNegative()
                || properties.getTimeout().isZero()) {
            throw new IllegalArgumentException("RAG 索引名称、维度或超时配置无效");
        }
        try {
            MinioClient minio = minio();
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RagConnectionException("MinIO 连接或桶初始化失败，请检查地址、凭证及桶权限", exception);
        }
        Map<String, Object> fields = Map.of(
                "ownerId", Map.of("type", "keyword"), "documentId", Map.of("type", "keyword"),
                "kind", Map.of("type", "keyword"),
                "filename", Map.of("type", "keyword"), "text", Map.of("type", "text"),
                "createdAt", Map.of("type", "date"),
                "vector", Map.of("type", "dense_vector", "dims", properties.getDimensions(), "index", false));
        HttpResponse<String> response = send(properties.getElasticsearchUrl(), properties.getElasticsearchApiKey(),
                "PUT", "/" + properties.getIndex(), Map.of("mappings", Map.of("properties", fields)));
        if (response.statusCode() != 200 && !mapper.readTree(response.body()).path("error").path("type")
                .asText().equals("resource_already_exists_exception")) {
            throw elasticsearchFailure(response.statusCode());
        }
        initialized = true;
    }

    /** 执行 ES 请求，响应正文不作为错误信息暴露。 */
    public JsonNode elastic(String method, String path, Object body) throws Exception {
        return elasticAt(properties.getIndex(), method, path, body);
    }

    /** 按文档保存的索引位置操作，允许配置调整后清理旧向量。 */
    public JsonNode elasticAt(String index, String method, String path, Object body) throws Exception {
        if (index == null || !index.matches("[a-z0-9][a-z0-9_-]{0,199}")) {
            throw new RagConnectionException("Elasticsearch 索引名称无效");
        }
        HttpResponse<String> response = send(properties.getElasticsearchUrl(), properties.getElasticsearchApiKey(),
                method, "/" + index + path, body);
        if (response.statusCode() == 404 && path.startsWith("/_delete_by_query")) {
            return mapper.createObjectNode();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw elasticsearchFailure(response.statusCode());
        }
        JsonNode result = mapper.readTree(response.body());
        if (result.path("failures").size() > 0 || result.path("timed_out").asBoolean()) {
            throw new IOException("Elasticsearch 操作未完成");
        }
        return result;
    }

    /** 调用兼容 OpenAI 的 embeddings 接口，校验维度及非零有限向量。 */
    public List<Double> embed(String text) throws Exception {
        HttpResponse<String> response = send(properties.getEmbeddingUrl(), properties.getEmbeddingApiKey(),
                "POST", "/embeddings", Map.of("model", properties.getEmbeddingModel(), "input", text));
        if (response.statusCode() != 200) {
            throw embeddingFailure(response);
        }
        JsonNode vector = mapper.readTree(response.body()).path("data").path(0).path("embedding");
        if (!vector.isArray() || vector.size() != properties.getDimensions()) {
            throw new RagConnectionException("Embedding 返回的向量维度与配置不匹配（配置 "
                    + properties.getDimensions() + "，返回 " + vector.size() + "），请核对模型维度和索引配置", false);
        }
        List<Double> values = new ArrayList<>();
        double norm = 0;
        for (JsonNode item : vector) {
            double value = item.asDouble();
            if (!item.isNumber() || !Double.isFinite(value)) {
                throw new RagConnectionException("Embedding 返回了无效向量，请检查模型服务");
            }
            values.add(value);
            norm += value * value;
        }
        if (norm == 0 || !Double.isFinite(norm)) {
            throw new RagConnectionException("Embedding 返回了无效向量，请检查模型服务");
        }
        return values;
    }

    /** 上传原文，存储键由业务层生成。 */
    public void put(String key, byte[] bytes) throws Exception {
        minio().putObject(PutObjectArgs.builder().bucket(properties.getBucket()).object(key)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("application/octet-stream").build());
    }

    /** 读取原文并关闭底层响应流。 */
    public byte[] get(String key) throws Exception {
        return getAt(properties.getBucket(), key);
    }

    /** 从文档记录的存储位置读取原文，不依赖当前桶配置。 */
    public byte[] getAt(String bucket, String key) throws Exception {
        try (var stream = minio().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        }
    }

    /** 删除指定原文；不存在的对象按幂等语义处理。 */
    public void remove(String key) throws Exception {
        removeAt(properties.getBucket(), key);
    }

    /** 删除文档记录位置的对象。 */
    public void removeAt(String bucket, String key) throws Exception {
        minio().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    private synchronized MinioClient minio() {
        if (minioClient == null) {
            minioClient = MinioClient.builder().endpoint(properties.getMinioEndpoint())
                    .credentials(properties.getMinioAccessKey(), properties.getMinioSecretKey()).build();
            minioClient.setTimeout(10000, properties.getTimeout().toMillis(), properties.getTimeout().toMillis());
        }
        return minioClient;
    }

    private HttpResponse<String> send(String base, String key, String method, String path, Object body)
            throws IOException, InterruptedException {
        boolean elasticsearch = base.equals(properties.getElasticsearchUrl());
        var builder = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path))
                .timeout(properties.getTimeout()).header("Content-Type", "application/json");
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", (elasticsearch ? "ApiKey " : "Bearer ") + key);
        } else if (elasticsearch && !properties.getElasticsearchUsername().isBlank()) {
            String credentials = properties.getElasticsearchUsername() + ":" + properties.getElasticsearchPassword();
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        try {
            return (elasticsearch ? elasticsearchClient() : client)
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (SSLHandshakeException exception) {
            throw new RagConnectionException(elasticsearch
                    ? "Elasticsearch TLS 证书验证失败，请配置正确的 CA 证书并检查连接主机名"
                    : "Embedding TLS 证书验证失败，请检查服务证书", exception, false);
        }
    }

    /** 仅对 ES 使用指定 CA，保留证书链及主机名验证，不影响 Embedding 客户端。 */
    private synchronized HttpClient elasticsearchClient() throws RagConnectionException {
        if (elasticsearchClient != null) {
            return elasticsearchClient;
        }
        if (properties.getElasticsearchCaCertificate().isBlank()) {
            elasticsearchClient = client;
            return elasticsearchClient;
        }
        try (var input = Files.newInputStream(Path.of(properties.getElasticsearchCaCertificate()))) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new IOException("CA certificate is empty");
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (var certificate : certificates) {
                trustStore.setCertificateEntry("elasticsearch-ca-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            elasticsearchClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                    .sslContext(context).build();
            return elasticsearchClient;
        } catch (Exception exception) {
            throw new RagConnectionException("Elasticsearch CA 证书加载失败，请检查证书路径及 PEM/DER 格式", exception, false);
        }
    }

    private static RagConnectionException elasticsearchFailure(int status) {
        return switch (status) {
            case 401 -> new RagConnectionException("Elasticsearch 认证失败（HTTP 401），请检查 API Key 或账号密码；账号密码不能填入 API Key 字段", false);
            case 403 -> new RagConnectionException("Elasticsearch 权限不足（HTTP 403），请检查索引创建、读写和删除权限", false);
            default -> new RagConnectionException("Elasticsearch 请求失败（HTTP " + status + "），请检查服务状态及索引配置");
        };
    }

    /** 仅识别已知错误码，不把上游响应正文或凭证传递给客户端。 */
    private RagConnectionException embeddingFailure(HttpResponse<String> response) {
        String code = "";
        String type = "";
        try {
            JsonNode body = mapper.readTree(response.body());
            JsonNode error = body == null ? mapper.createObjectNode() : body.path("error");
            code = error.path("code").asText();
            type = error.path("type").asText();
        } catch (IOException ignored) {
            // 非 JSON 错误响应仅按 HTTP 状态归类。
        }
        if ("insufficient_quota".equals(code) || "insufficient_quota".equals(type)) {
            return new RagConnectionException("Embedding 服务额度不足，请在模型平台检查免费额度、账户余额或“仅使用免费额度”设置；也可配置其他可用的 Embedding 服务", false);
        }
        return switch (response.statusCode()) {
            case 401 -> new RagConnectionException("Embedding 认证失败（HTTP 401），请检查 Embedding API Key", false);
            case 403 -> new RagConnectionException("Embedding 访问被拒绝（HTTP 403），请检查模型权限及账户额度", false);
            case 404 -> new RagConnectionException("Embedding 接口或模型不存在（HTTP 404），请检查服务地址及模型名称", false);
            case 429 -> new RagConnectionException("Embedding 请求受限（HTTP 429），请检查调用频率及账户额度后重试");
            default -> new RagConnectionException("Embedding 请求失败（HTTP " + response.statusCode()
                    + "），请检查模型服务配置及可用状态");
        };
    }
}
