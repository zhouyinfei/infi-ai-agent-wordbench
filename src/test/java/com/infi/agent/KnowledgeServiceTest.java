package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.RagProperties;
import com.infi.agent.entity.KnowledgeDocumentDO;
import com.infi.agent.mapper.KnowledgeDocumentMapper;
import com.infi.agent.rag.DocumentParser;
import com.infi.agent.rag.RagConnectionException;
import com.infi.agent.rag.RagInfrastructure;
import com.infi.agent.service.impl.KnowledgeServiceImpl;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证 MySQL 发布边界、用户隔离及失败补偿。 */
class KnowledgeServiceTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private final ObjectMapper json = new ObjectMapper();
    private RagInfrastructure infrastructure;
    private KnowledgeDocumentMapper documents;
    private KnowledgeServiceImpl service;
    private RagProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        properties = new RagProperties();
        properties.setEnabled(true);
        infrastructure = mock(RagInfrastructure.class);
        documents = mock(KnowledgeDocumentMapper.class);
        service = new KnowledgeServiceImpl(properties, infrastructure, new DocumentParser(), documents);
        when(documents.list(anyLong())).thenReturn(List.of());
        when(documents.complete(anyString(), anyLong(), anyString(), nullable(String.class), any())).thenReturn(1);
        when(documents.beginDelete(anyString(), anyLong(), any(), any())).thenReturn(1);
        when(infrastructure.elastic(anyString(), anyString(), any())).thenReturn(json.readTree("{\"hits\":{\"hits\":[]}}"));
        when(infrastructure.embed(anyString())).thenReturn(List.of(1.0, 0.0));
    }

    @Test
    void listReadsOnlyMysqlEvenWhenRagIsDisabled() {
        properties.setEnabled(false);
        when(documents.list(7L)).thenReturn(List.of(row("FAILED")));
        assertThat(service.list(7L)).singleElement().satisfies(item -> assertThat(item.status()).isEqualTo("FAILED"));
        verifyNoInteractions(infrastructure);
    }

    @Test
    void downloadCannotAccessOtherUsersDocument() {
        assertThatThrownBy(() -> service.download(2L, ID)).isInstanceOf(BusinessException.class).hasMessage("文档不存在");
        verify(documents).selectOwned(ID, 2L);
        verifyNoInteractions(infrastructure);
    }

    @Test
    void emptyKnowledgeDoesNotCallExternalServices() {
        assertThat(service.search(1L, "问题", 5)).isEmpty();
        verifyNoInteractions(infrastructure);
    }

    @Test
    void publishesInMysqlOnlyAfterChunksAreVisible() throws Exception {
        var result = service.upload(7L, file());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.filename()).isEqualTo("资料.md");
        var order = inOrder(documents, infrastructure);
        order.verify(documents).count(7L);
        order.verify(documents).insert(any());
        order.verify(infrastructure).initialize();
        order.verify(infrastructure).put(eq("7/" + result.id()), any());
        order.verify(infrastructure).embed("真实参考资料");
        order.verify(infrastructure).elastic(eq("PUT"), eq("/_doc/" + result.id() + "-0"), any());
        order.verify(infrastructure).elastic("POST", "/_refresh", null);
        order.verify(documents).complete(eq(result.id()), eq(7L), eq("READY"), isNull(), any());
        verify(infrastructure, never()).elastic(eq("PUT"), contains("?refresh=wait_for"), any());
    }

    @Test
    void embeddingFailurePersistsSafeReasonInMysql() throws Exception {
        when(infrastructure.embed(anyString())).thenThrow(new RagConnectionException("Embedding 服务额度不足"));
        assertThatThrownBy(() -> service.upload(7L, file())).isInstanceOf(BusinessException.class).hasMessageContaining("额度不足");
        verify(documents).complete(anyString(), eq(7L), eq("FAILED"), eq("Embedding 服务额度不足"), any());
        verify(documents, never()).complete(anyString(), anyLong(), eq("READY"), any(), any());
    }

    @Test
    void initializationFailureLeavesFailedMetadata() throws Exception {
        doThrow(new RagConnectionException("MinIO 连接失败")).when(infrastructure).initialize();
        assertThatThrownBy(() -> service.upload(7L, file())).isInstanceOf(BusinessException.class);
        verify(documents).complete(anyString(), eq(7L), eq("FAILED"), eq("MinIO 连接失败"), any());
    }

    @Test
    void searchFiltersMysqlReadyIdsAndModelCompatibility() throws Exception {
        var failed = row("FAILED");
        failed.setId("failed-id");
        var foreignModel = row("READY");
        foreignModel.setId("other-model-id");
        foreignModel.setEmbeddingModel("other-model");
        when(documents.list(7L)).thenReturn(List.of(row("READY"), failed, foreignModel));
        service.search(7L, "核心能力", 3);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(infrastructure, times(2)).elastic(eq("POST"), eq("/_search"), body.capture());
        assertThat(json.writeValueAsString(body.getValue())).contains("\"ownerId\":\"7\"", ID)
                .doesNotContain("failed-id", "other-model-id");
    }

    @Test
    void permanentRetrievalFailurePreservesNonRetryableCodeForToolRegistry() throws Exception {
        when(documents.list(7L)).thenReturn(List.of(row("READY")));
        when(infrastructure.embed(anyString())).thenThrow(new RagConnectionException("Embedding 服务额度不足", false));
        assertThatThrownBy(() -> service.search(7L, "问题", 5)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("RAG_NON_RETRYABLE"));
    }

    @Test
    void deleteUsesSavedLocationsAfterMysqlWithdrawsPublication() throws Exception {
        var document = row("READY");
        document.setVectorIndex("old-index");
        document.setStorageBucket("old-bucket");
        when(documents.selectOwned(ID, 7L)).thenReturn(document);
        service.delete(7L, ID);
        var order = inOrder(documents, infrastructure);
        order.verify(documents).selectOwned(ID, 7L);
        order.verify(documents).beginDelete(eq(ID), eq(7L), any(), any());
        order.verify(infrastructure).elasticAt(eq("old-index"), eq("POST"), contains("/_delete_by_query"), any());
        order.verify(infrastructure).removeAt("old-bucket", "7/" + ID);
        order.verify(documents).delete(eq(ID), eq(7L), any());
    }

    @Test
    void deleteFailureKeepsMetadataForRetry() throws Exception {
        when(documents.selectOwned(ID, 7L)).thenReturn(row("READY"));
        doThrow(new RagConnectionException("ES 暂不可用")).when(infrastructure).elasticAt(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.delete(7L, ID)).isInstanceOf(BusinessException.class);
        verify(documents).deleteFailed(eq(ID), eq(7L), eq("ES 暂不可用"), any());
        verify(documents, never()).delete(anyString(), anyLong(), any());
    }

    private KnowledgeDocumentDO row(String status) {
        KnowledgeDocumentDO row = new KnowledgeDocumentDO();
        row.setId(ID);
        row.setUserId(7L);
        row.setFilename("资料.md");
        row.setFileSize(100L);
        row.setChunkCount(1);
        row.setStatus(status);
        row.setStorageBucket(properties.getBucket());
        row.setObjectKey("7/" + ID);
        row.setVectorIndex(properties.getIndex());
        row.setEmbeddingModel(properties.getEmbeddingModel());
        row.setEmbeddingDimensions(properties.getDimensions());
        row.setGmtCreate(LocalDateTime.now(ZoneOffset.UTC));
        return row;
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "../资料.md", "text/markdown", "真实参考资料".getBytes(StandardCharsets.UTF_8));
    }
}
