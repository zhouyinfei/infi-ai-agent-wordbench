package com.infi.agent.service.impl;

import com.infi.agent.common.BusinessException;
import com.infi.agent.config.RagProperties;
import com.infi.agent.entity.KnowledgeDocumentDO;
import com.infi.agent.mapper.KnowledgeDocumentMapper;
import com.infi.agent.rag.DocumentParser;
import com.infi.agent.rag.HybridKnowledgeRetriever;
import com.infi.agent.rag.RagInfrastructure;
import com.infi.agent.service.KnowledgeService;
import com.infi.agent.vo.KnowledgeDocumentVO;
import com.infi.agent.vo.KnowledgeHitVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** MySQL 管理文档状态，MinIO 保存原文，ES 仅接收分块及向量；跨存储采用可重试补偿。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_DOCUMENTS = 500;
    private final RagProperties properties;
    private final RagInfrastructure infrastructure;
    private final DocumentParser parser;
    private final KnowledgeDocumentMapper documents;

    @Override
    public List<KnowledgeDocumentVO> list(Long ownerId) {
        // 元信息读取只依赖 MySQL，ES 或 MinIO 离线时仍可查看状态和失败原因。
        return documents.list(ownerId).stream().map(KnowledgeServiceImpl::view).toList();
    }
    @Override
    public KnowledgeDocumentVO upload(Long ownerId, MultipartFile file) {
        enabled();
        if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw invalid("文件不能为空且不得超过 10 MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank() || filename.length() > 200
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw invalid("文件名称无效或超过 200 字符");
        }
        // 用户提供的文件名只作为展示字段，不参与对象路径生成。
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        String id = UUID.randomUUID().toString();
        KnowledgeDocumentDO metadata = new KnowledgeDocumentDO();
        boolean registered = false;
        try {
            if (documents.count(ownerId) >= MAX_DOCUMENTS) {
                throw invalid("每位用户最多保存 500 个文档，请先删除旧文档");
            }
            byte[] bytes = file.getBytes();
            String text;
            try {
                text = parser.parse(filename, bytes, properties.getMaxCharacters()).strip();
            } catch (java.io.IOException exception) {
                throw invalid("文档无法解析或超过文本长度限制，请检查文件格式、内容或加密状态");
            }
            if (text.isBlank() || text.length() > properties.getMaxCharacters()) {
                throw invalid("文档无可提取文本或超过文本长度限制；扫描 PDF 请先进行 OCR");
            }
            List<String> chunks = parser.split(text, properties.getChunkSize(), properties.getChunkOverlap());
            metadata.setId(id);
            metadata.setUserId(ownerId);
            metadata.setFilename(filename);
            metadata.setFileSize(file.getSize());
            metadata.setChunkCount(chunks.size());
            metadata.setStatus("INDEXING");
            metadata.setStorageBucket(properties.getBucket());
            metadata.setObjectKey(key(ownerId, id));
            metadata.setVectorIndex(properties.getIndex());
            metadata.setEmbeddingModel(properties.getEmbeddingModel());
            metadata.setEmbeddingDimensions(properties.getDimensions());
            metadata.setGmtCreate(LocalDateTime.now(ZoneOffset.UTC));
            metadata.setGmtModified(metadata.getGmtCreate());
            documents.insert(metadata);
            registered = true;
            infrastructure.initialize();
            infrastructure.put(key(ownerId, id), bytes);
            for (int index = 0; index < chunks.size(); index++) {
                infrastructure.elastic("PUT", "/_doc/" + id + "-" + index, Map.of(
                        "ownerId", ownerId.toString(), "kind", "chunk", "documentId", id,
                        "filename", filename, "chunk", index + 1, "text", chunks.get(index),
                        "vector", infrastructure.embed(chunks.get(index))));
            }
            // refresh 屏障保证发布元信息前所有分块均已可检索。
            infrastructure.elastic("POST", "/_refresh", null);
            if (documents.complete(id, ownerId, "READY", null, LocalDateTime.now(ZoneOffset.UTC)) != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "DOCUMENT_STATE_CHANGED", "文档状态已变化，未发布索引");
            }
            metadata.setStatus("READY");
            return view(metadata);
        } catch (Exception exception) {
            if (registered) {
                try {
                    documents.complete(id, ownerId, "FAILED", unavailable(exception).getMessage(), LocalDateTime.now(ZoneOffset.UTC));
                } catch (Exception cleanupException) {
                    log.warn("RAG status update failed, documentId={}", id);
                }
            }
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            if (exception instanceof IllegalArgumentException) {
                throw invalid(exception.getMessage());
            }
            throw unavailable(exception);
        }
    }

    @Override
    public List<KnowledgeHitVO> search(Long ownerId, String query, int limit) {
        enabled();
        if (query == null || query.isBlank() || query.length() > 2000 || limit < 1 || limit > 10) {
            throw invalid("检索问题须为 1–2000 字符，结果数须为 1–10");
        }
        try {
            List<String> ids = documents.list(ownerId).stream()
                    .filter(item -> "READY".equals(item.getStatus()) && properties.getIndex().equals(item.getVectorIndex())
                            && properties.getEmbeddingModel().equals(item.getEmbeddingModel())
                            && properties.getDimensions() == item.getEmbeddingDimensions())
                    .map(KnowledgeDocumentDO::getId).toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            return new HybridKnowledgeRetriever(properties, infrastructure).search(ownerId, ids, query, limit);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public KnowledgeDocumentVO document(Long ownerId, String id) {
        return view(owned(ownerId, id));
    }

    private KnowledgeDocumentDO owned(Long ownerId, String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) {
            throw invalid("文档编号无效");
        }
        KnowledgeDocumentDO document = documents.selectOwned(id, ownerId);
        if (document == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "文档不存在");
        }
        return document;
    }

    @Override
    public byte[] download(Long ownerId, String id) {
        KnowledgeDocumentDO document = owned(ownerId, id);
        try {
            return infrastructure.getAt(document.getStorageBucket(), document.getObjectKey());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void delete(Long ownerId, String id) {
        KnowledgeDocumentDO document = owned(ownerId, id);
        if (documents.beginDelete(id, ownerId, LocalDateTime.now(ZoneOffset.UTC).minusHours(1), LocalDateTime.now(ZoneOffset.UTC)) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "DOCUMENT_BUSY", "文档正在索引，请稍后删除");
        }
        try {
            infrastructure.elasticAt(document.getVectorIndex(), "POST", "/_delete_by_query?refresh=true",
                    Map.of("query", filters(ownerId, Map.of("term", Map.of("documentId", id)))));
            infrastructure.removeAt(document.getStorageBucket(), document.getObjectKey());
            documents.delete(id, ownerId, LocalDateTime.now(ZoneOffset.UTC));
        } catch (Exception exception) {
            BusinessException failure = unavailable(exception);
            try {
                documents.deleteFailed(id, ownerId, failure.getMessage(), LocalDateTime.now(ZoneOffset.UTC));
            } catch (RuntimeException cleanupException) {
                log.warn("RAG delete status update failed, documentId={}", id);
            }
            throw failure;
        }
    }
    private void enabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "RAG_DISABLED", "知识库尚未启用，请配置 MinIO、Elasticsearch 和 Embedding 服务");
        }
    }
    @SafeVarargs
    private static Map<String, Object> filters(Long ownerId, Map<String, Object>... additional) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("ownerId", ownerId.toString())));
        filters.addAll(List.of(additional));
        return Map.of("bool", Map.of("filter", filters));
    }

    private static KnowledgeDocumentVO view(KnowledgeDocumentDO source) {
        return new KnowledgeDocumentVO(source.getId(), source.getFilename(), source.getFileSize(), source.getChunkCount(),
                source.getStatus(), source.getGmtCreate().toInstant(ZoneOffset.UTC).toString(), source.getErrorMessage());
    }
    private static String key(Long ownerId, String id) {
        return ownerId + "/" + id;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", message);
    }

    private static BusinessException unavailable(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.warn("RAG operation failed, exceptionType={}", exception.getClass().getName());
        if (exception instanceof com.infi.agent.rag.RagConnectionException connectionException) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    connectionException.isRetryable() ? "RAG_CONNECTION_FAILED" : "RAG_NON_RETRYABLE", exception.getMessage());
        }
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "RAG_UNAVAILABLE", "知识库服务不可用，请检查存储、向量及 Embedding 配置；失败文档可删除后重新上传");
    }
}
