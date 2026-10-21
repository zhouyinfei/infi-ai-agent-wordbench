package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.RagProperties;
import com.infi.agent.rag.HybridKnowledgeRetriever;
import com.infi.agent.rag.RagConnectionException;
import com.infi.agent.rag.RagInfrastructure;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证混合召回、关键词补充候选、硬阈值和 RRF 排序，不依赖外部模型。 */
class HybridKnowledgeRetrieverTest {
    private final ObjectMapper json = new ObjectMapper();
    private RagProperties properties;
    private RagInfrastructure infrastructure;
    private HybridKnowledgeRetriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        properties = new RagProperties();
        infrastructure = mock(RagInfrastructure.class);
        when(infrastructure.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        retriever = new HybridKnowledgeRetriever(properties, infrastructure);
    }

    @Test
    void keywordMatchReranksSemanticallyEligibleCandidates() throws Exception {
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any()))
                .thenReturn(response(hit("a", 0.9), hit("b", 0.8)), response(hit("b", 8)),
                        response(hit("a", 0.9), hit("b", 0.8)));
        var result = retriever.search(7L, List.of("doc"), "林知夏", 5);
        assertThat(result).extracting(item -> item.chunk()).containsExactly(2, 1);
        assertThat(result.get(0).score()).isCloseTo(0.8, within(0.00001));
        assertThat(result.get(0).rerankScore()).isGreaterThan(result.get(1).rerankScore());
        verify(infrastructure, times(1)).embed("林知夏");
    }

    @Test
    void keywordOnlyCandidatesCannotBypassCosineThreshold() throws Exception {
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any())).thenReturn(response(hit("a", 0.8)),
                response(hit("b", 9), hit("c", 8)), response(hit("a", 0.8), hit("b", 0.7), hit("c", 0.1)));
        var result = retriever.search(7L, List.of("doc"), "人物介绍", 5);
        assertThat(result).hasSize(2).allSatisfy(item -> assertThat(item.score()).isGreaterThanOrEqualTo(0.55));
        assertThat(result).extracting(item -> item.chunk()).contains(2).doesNotContain(3);
        ArgumentCaptor<Object> requests = ArgumentCaptor.forClass(Object.class);
        verify(infrastructure, times(3)).elastic(eq("POST"), eq("/_search"), requests.capture());
        JsonNode last = json.valueToTree(requests.getAllValues().get(2));
        assertThat(last.path("size").asInt()).isEqualTo(3);
        assertThat(last.toString()).contains("\"ids\"", "\"a\"", "\"b\"", "\"c\"");
        assertThat(last.path("min_score").asDouble()).isEqualTo(1.55);
        for (Object request : requests.getAllValues()) {
            assertThat(json.writeValueAsString(request)).contains("\"ownerId\":\"7\"", "\"documentId\":[\"doc\"]", "chunk");
        }
    }

    @Test
    void insufficientRelevanceReturnsEmptyRatherThanFillingTopK() throws Exception {
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any())).thenReturn(response(hit("a", 0.3)),
                response(hit("a", 8)), response(hit("a", 0.3)));
        assertThat(retriever.search(7L, List.of("doc"), "无关问题", 5)).isEmpty();
    }

    @Test
    void emptyRecallStopsBeforeFinalRescoring() throws Exception {
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any())).thenReturn(response());
        assertThat(retriever.search(7L, List.of("doc"), "问题", 5)).isEmpty();
        verify(infrastructure, times(2)).elastic(eq("POST"), eq("/_search"), any());
    }

    @Test
    void vectorOnlyModeAppliesConfiguredThresholdAndReturnsFewerResults() throws Exception {
        properties.getRetrieval().setHybridEnabled(false);
        properties.getRetrieval().setMinScore(0.7);
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any())).thenReturn(response(hit("a", 0.8), hit("b", 0.6)));
        assertThat(retriever.search(7L, List.of("doc"), "问题", 5)).singleElement()
                .satisfies(item -> assertThat(item.rerankScore()).isNull());
        verify(infrastructure, times(1)).elastic(eq("POST"), eq("/_search"), any());
    }

    @Test
    void thresholdBoundaryUsesElasticFloatPrecision() throws Exception {
        properties.getRetrieval().setHybridEnabled(false);
        var boundary = Map.of("_id", "a", "_score", (double) (float) 1.55,
                "_source", Map.of("documentId", "doc", "filename", "资料.txt", "chunk", 1, "text", "边界"));
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any())).thenReturn(response(boundary, hit("b", 0.549)));
        assertThat(retriever.search(7L, List.of("doc"), "问题", 5)).hasSize(1);
    }

    @Test
    void invalidConfigurationFailsBeforeEmbedding() {
        properties.getRetrieval().setMinScore(Double.NaN);
        assertThatThrownBy(() -> retriever.search(7L, List.of("doc"), "问题", 5))
                .isInstanceOf(RagConnectionException.class)
                .satisfies(error -> assertThat(((RagConnectionException) error).isRetryable()).isFalse());
        verifyNoInteractions(infrastructure);
    }

    @Test
    void duplicateRecallEntriesProduceOneResultAndLimitIsRespected() throws Exception {
        when(infrastructure.elastic(eq("POST"), eq("/_search"), any())).thenReturn(response(hit("a", 0.9), hit("b", 0.8)),
                response(hit("a", 5), hit("b", 4)), response(hit("a", 0.9), hit("b", 0.8)));
        assertThat(retriever.search(7L, List.of("doc"), "问题", 1)).hasSize(1);
    }

    private Map<String, Object> hit(String id, double cosine) {
        return Map.of("_id", id, "_score", cosine + 1, "_source", Map.of("documentId", "doc", "filename", "资料.txt",
                "chunk", id.charAt(0) - 'a' + 1, "text", "资料" + id));
    }

    @SafeVarargs
    private final JsonNode response(Map<String, Object>... hits) {
        return json.valueToTree(Map.of("hits", Map.of("hits", List.of(hits))));
    }
}
