package com.infi.agent.service;

import com.infi.agent.vo.KnowledgeDocumentVO;
import com.infi.agent.vo.KnowledgeHitVO;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 按用户隔离的文档及语义检索业务接口。 */
public interface KnowledgeService {
    /** @return 当前用户的文档，按创建时间倒序 */
    List<KnowledgeDocumentVO> list(Long ownerId);
    /** 解析、存储及向量化文件，仅在全部分块可检索后发布。 */
    KnowledgeDocumentVO upload(Long ownerId, MultipartFile file);
    /** @return 当前用户已发布文档中与问题最相似的有序片段 */
    List<KnowledgeHitVO> search(Long ownerId, String query, int limit);
    /** 校验文档归属后返回原文字节。 */
    byte[] download(Long ownerId, String id);
    /** @return 当前用户指定文档，其他用户的文档统一视为不存在 */
    KnowledgeDocumentVO document(Long ownerId, String id);
    /** 撤销发布并依次删除向量、原文及元信息，失败后允许重试。 */
    void delete(Long ownerId, String id);
}
