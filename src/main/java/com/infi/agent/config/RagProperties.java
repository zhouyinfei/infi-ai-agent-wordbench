package com.infi.agent.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** RAG 服务端配置；凭证不通过接口返回。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {
    private boolean enabled;
    private String minioEndpoint = "http://localhost:9000";
    private String minioAccessKey = "";
    private String minioSecretKey = "";
    private String bucket = "agent-knowledge";
    private String elasticsearchUrl = "http://localhost:9200";
    private String elasticsearchApiKey = "";
    private String elasticsearchUsername = "";
    private String elasticsearchPassword = "";
    private String elasticsearchCaCertificate = "";
    private String index = "agent-knowledge-v1";
    private String embeddingUrl = "https://api.openai.com/v1";
    private String embeddingApiKey = "";
    private String embeddingModel = "text-embedding-3-small";
    private int dimensions = 1536;
    private int chunkSize = 800;
    private int chunkOverlap = 120;
    private int maxCharacters = 200000;
    private int maxSearchesPerRun = 3;
    private Retrieval retrieval = new Retrieval();
    private Duration timeout = Duration.ofSeconds(60);

    /** 检索相关性配置：分数门槛基于原始余弦值，不使用 ES 平移后的分数。 */
    @Getter
    @Setter
    public static class Retrieval {
        private double minScore = 0.55;
        private boolean hybridEnabled = true;
        private int candidateSize = 30;
        private int rrfK = 60;
        private double vectorWeight = 0.7;
        private double keywordWeight = 0.3;

        /** 拒绝不合法的阈值、预算和权重，避免悄悄放宽过滤。 */
        public void validate() {
            if (!Double.isFinite(minScore) || minScore < -1 || minScore > 1
                    || candidateSize < 10 || candidateSize > 200 || rrfK < 1 || rrfK > 1000
                    || !Double.isFinite(vectorWeight) || !Double.isFinite(keywordWeight)
                    || vectorWeight <= 0 || keywordWeight <= 0
                    || !Double.isFinite(vectorWeight + keywordWeight)) {
                throw new IllegalArgumentException("RAG 检索配置无效：min-score 须为 -1–1，candidate-size 为 10–200，rrf-k 为 1–1000，权重须为正有限数");
            }
        }
    }
}
