-- 在现有业务数据库执行；不修改用户、会话或既有文档。
CREATE TABLE IF NOT EXISTS ai_knowledge_document (
  id CHAR(36) NOT NULL COMMENT '文档UUID',
  user_id BIGINT NOT NULL COMMENT '所属用户',
  filename VARCHAR(200) NOT NULL COMMENT '展示文件名',
  file_size BIGINT NOT NULL COMMENT '原文字节数',
  chunk_count INT NOT NULL DEFAULT 0 COMMENT '计划分块数量',
  status VARCHAR(20) NOT NULL COMMENT 'INDEXING READY FAILED DELETING',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '脱敏后的处理失败原因',
  storage_bucket VARCHAR(63) NOT NULL COMMENT 'MinIO桶',
  object_key VARCHAR(300) NOT NULL COMMENT 'MinIO对象键',
  vector_index VARCHAR(200) NOT NULL COMMENT 'ES向量索引',
  embedding_model VARCHAR(200) NOT NULL COMMENT '向量模型标识',
  embedding_dimensions INT NOT NULL COMMENT '向量维度',
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间UTC',
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间UTC',
  PRIMARY KEY (id),
  KEY idx_knowledge_user_created (user_id, gmt_create),
  KEY idx_knowledge_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档元信息';
