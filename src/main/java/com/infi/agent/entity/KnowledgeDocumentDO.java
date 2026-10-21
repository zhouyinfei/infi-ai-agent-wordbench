package com.infi.agent.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 知识文档元信息；正文保存在 MinIO，分块和向量保存在 ES。 */
@Data
public class KnowledgeDocumentDO {
    private String id;
    private Long userId;
    private String filename;
    private Long fileSize;
    private Integer chunkCount;
    private String status;
    private String errorMessage;
    private String storageBucket;
    private String objectKey;
    private String vectorIndex;
    private String embeddingModel;
    private Integer embeddingDimensions;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
