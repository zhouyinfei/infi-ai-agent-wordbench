package com.infi.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.infi.agent.config.RagProperties;
import com.infi.agent.vo.KnowledgeHitVO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/** 请求内的混合召回及本地 RRF 重排；三轮查询均执行相同用户及 READY 文档过滤。 */
@RequiredArgsConstructor
public final class HybridKnowledgeRetriever {
    private static final List<String> SOURCE_FIELDS = List.of("documentId", "filename", "chunk", "text");
    private final RagProperties properties;
    private final RagInfrastructure infrastructure;

    /** 使用一次问题向量完成候选召回和阈值过滤，不调用聊天模型或额外重排模型。 */
    public List<KnowledgeHitVO> search(Long ownerId, List<String> documentIds, String query, int limit) throws Exception {
        RagProperties.Retrieval settings = properties.getRetrieval();
        try {
            if (settings == null) {
                throw new IllegalArgumentException("检索配置缺失");
            }
            settings.validate();
        } catch (IllegalArgumentException exception) {
            throw new RagConnectionException(exception.getMessage(), false);
        }
        List<Double> vector = infrastructure.embed(query);
        Map<String, Object> allowed = allowed(ownerId, documentIds);
        JsonNode vectorHits = vectorSearch(allowed, vector, settings.getCandidateSize(), settings.getMinScore());
        if (!settings.isHybridEnabled()) {
            return rank(vectorHits, Map.of(), settings.getMinScore(), limit);
        }
        // BM25 保留自身排序，使用 match 而非 query_string，用户内容不会作为查询语法执行。
        JsonNode keywordHits = hits(infrastructure.elastic("POST", "/_search", Map.of(
                "size", settings.getCandidateSize(), "_source", false,
                "query", Map.of("bool", Map.of("filter", List.of(allowed),
                        "must", List.of(Map.of("match", Map.of("text", Map.of("query", query)))))))));
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>();
        Map<String, Double> fusion = new LinkedHashMap<>();
        double totalWeight = settings.getVectorWeight() + settings.getKeywordWeight();
        addRanks(vectorHits, settings.getVectorWeight() / totalWeight, settings.getRrfK(), fusion, candidateIds);
        addRanks(keywordHits, settings.getKeywordWeight() / totalWeight, settings.getRrfK(), fusion, candidateIds);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        // 对关键词独立召回的候选重新计算真实余弦值，禁止绕过最低分门槛。
        Map<String, Object> candidates = Map.of("bool", Map.of("filter", List.of(allowed,
                Map.of("ids", Map.of("values", List.copyOf(candidateIds))))));
        JsonNode eligible = vectorSearch(candidates, vector, candidateIds.size(), settings.getMinScore());
        return rank(eligible, fusion, settings.getMinScore(), limit);
    }

    private JsonNode vectorSearch(Map<String, Object> query, List<Double> vector, int size, double minScore) throws Exception {
        Map<String, Object> script = Map.of("source", "cosineSimilarity(params.vector, 'vector') + 1.0",
                "params", Map.of("vector", vector));
        return hits(infrastructure.elastic("POST", "/_search", Map.of("size", size, "_source", SOURCE_FIELDS,
                "min_score", minScore + 1.0, "query", Map.of("script_score", Map.of("query", query, "script", script)))));
    }

    private static Map<String, Object> allowed(Long ownerId, List<String> ids) {
        return Map.of("bool", Map.of("filter", List.of(Map.of("term", Map.of("ownerId", ownerId.toString())),
                Map.of("term", Map.of("kind", "chunk")), Map.of("terms", Map.of("documentId", ids)))));
    }

    private static void addRanks(JsonNode hits, double weight, int k, Map<String, Double> scores,
                                 LinkedHashSet<String> candidateIds) {
        int rank = 0;
        for (JsonNode hit : hits) {
            String id = hit.path("_id").asText();
            rank++;
            if (!id.isBlank()) {
                candidateIds.add(id);
                scores.merge(id, weight / (k + rank), Double::sum);
            }
        }
    }

    private static List<KnowledgeHitVO> rank(JsonNode hits, Map<String, Double> fusion, double threshold, int limit) {
        List<KnowledgeHitVO> result = new ArrayList<>();
        for (JsonNode hit : hits) {
            double cosine = hit.path("_score").asDouble(Double.NaN) - 1.0;
            // ES 使用 float 保存分数；边界比较使用相同精度，避免 0.55 被表示为 0.54999995。
            if (!Double.isFinite(cosine) || (float) (cosine + 1.0) < (float) (threshold + 1.0)) {
                continue;
            }
            JsonNode source = hit.path("_source");
            Double score = fusion.isEmpty() ? null : fusion.get(hit.path("_id").asText());
            if (!fusion.isEmpty() && score == null) {
                continue;
            }
            result.add(new KnowledgeHitVO(source.path("documentId").asText(), source.path("filename").asText(),
                    source.path("chunk").asInt(), source.path("text").asText(), Math.max(-1, Math.min(1, cosine)), score));
        }
        Comparator<KnowledgeHitVO> order = fusion.isEmpty() ? Comparator.comparingDouble(KnowledgeHitVO::score).reversed()
                : Comparator.comparingDouble((KnowledgeHitVO hit) -> hit.rerankScore()).reversed()
                        .thenComparing(Comparator.comparingDouble(KnowledgeHitVO::score).reversed());
        return result.stream().sorted(order.thenComparing(KnowledgeHitVO::documentId).thenComparingInt(KnowledgeHitVO::chunk))
                .limit(limit).toList();
    }

    private static JsonNode hits(JsonNode response) {
        return response.path("hits").path("hits");
    }
}
