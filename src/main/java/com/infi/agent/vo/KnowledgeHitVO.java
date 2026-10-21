package com.infi.agent.vo;

/** 可追溯的检索结果；chunk 从 1 开始。 */
public record KnowledgeHitVO(String documentId, String filename, int chunk, String text, double score, Double rerankScore) {
    /** 兼容仅包含余弦相似度的调用方。 */
    public KnowledgeHitVO(String documentId, String filename, int chunk, String text, double score) {
        this(documentId, filename, chunk, text, score, null);
    }
}
