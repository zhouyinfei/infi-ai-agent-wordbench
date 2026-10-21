package com.infi.agent.vo;

/** 知识文档展示信息，不返回存储凭证或向量。 */
public record KnowledgeDocumentVO(String id, String filename, long size, int chunks, String status, String createdAt,
                                  String errorMessage) {
}
