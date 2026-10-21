package com.infi.agent;

import com.infi.agent.config.RagProperties;
import com.infi.agent.entity.KnowledgeDocumentDO;
import com.infi.agent.mapper.KnowledgeDocumentMapper;
import com.infi.agent.rag.DocumentParser;
import com.infi.agent.rag.RagConnectionException;
import com.infi.agent.rag.RagInfrastructure;
import com.infi.agent.service.impl.KnowledgeServiceImpl;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MyBatis/H2 验证状态提交、SQL 归属条件及故障重试。 */
class KnowledgePersistenceTest {
    private SqlSession session;
    private KnowledgeDocumentMapper documents;
    private RagInfrastructure infrastructure;
    private KnowledgeServiceImpl service;
    private RagProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        var source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:knowledge-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        session = RagTestDatabase.open(source);
        documents = session.getMapper(KnowledgeDocumentMapper.class);
        properties = new RagProperties();
        properties.setEnabled(true);
        infrastructure = mock(RagInfrastructure.class);
        when(infrastructure.embed(anyString())).thenReturn(java.util.List.of(1.0, 0.2));
        service = new KnowledgeServiceImpl(properties, infrastructure, new DocumentParser(), documents);
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Test
    void successfulUploadPersistsMetadataWithoutAnEsMetadataDocument() throws Exception {
        var result = service.upload(7L, file());
        KnowledgeDocumentDO row = documents.selectOwned(result.id(), 7L);
        assertThat(row.getStatus()).isEqualTo("READY");
        assertThat(row.getErrorMessage()).isNull();
        assertThat(row.getObjectKey()).isEqualTo("7/" + result.id());
        assertThat(documents.selectOwned(result.id(), 8L)).isNull();
        assertThat(documents.list(8L)).isEmpty();
        verify(infrastructure, never()).elastic(eq("PUT"), contains("?refresh=wait_for"), any());
    }

    @Test
    void failedUploadCommitsReasonAndListRemainsAvailableDuringStorageOutage() throws Exception {
        when(infrastructure.embed(anyString())).thenThrow(new RagConnectionException("Embedding 服务额度不足"));
        assertThatThrownBy(() -> service.upload(7L, file())).hasMessageContaining("额度不足");
        reset(infrastructure);
        assertThat(service.list(7L)).singleElement().satisfies(document -> {
            assertThat(document.status()).isEqualTo("FAILED");
            assertThat(document.errorMessage()).isEqualTo("Embedding 服务额度不足");
        });
        verifyNoInteractions(infrastructure);
    }

    @Test
    void sqlStateTransitionsPreventPublishAfterDeleteAndRejectForeignUsers() throws Exception {
        var result = service.upload(7L, file());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(documents.beginDelete(result.id(), 8L, now, now)).isZero();
        assertThat(documents.beginDelete(result.id(), 7L, now, now)).isOne();
        assertThat(documents.complete(result.id(), 7L, "READY", null, now)).isZero();
        assertThat(documents.delete(result.id(), 8L, now)).isZero();
        assertThat(documents.selectOwned(result.id(), 7L).getStatus()).isEqualTo("DELETING");
    }

    @Test
    void partialDeletionPreservesRetryableStatusAndOriginalError() throws Exception {
        var result = service.upload(7L, file());
        doThrow(new RagConnectionException("ES 暂不可用")).when(infrastructure)
                .elasticAt(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.delete(7L, result.id())).hasMessageContaining("ES 暂不可用");
        assertThat(documents.selectOwned(result.id(), 7L).getStatus()).isEqualTo("DELETING");
        assertThat(documents.selectOwned(result.id(), 7L).getErrorMessage()).isEqualTo("ES 暂不可用");
        reset(infrastructure);
        service.delete(7L, result.id());
        assertThat(documents.selectOwned(result.id(), 7L)).isNull();
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "资料.txt", "text/plain", "knowledge".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
