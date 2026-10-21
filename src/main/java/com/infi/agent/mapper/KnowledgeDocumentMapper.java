package com.infi.agent.mapper;

import com.infi.agent.entity.KnowledgeDocumentDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 文档元信息的数据访问，所有用户操作均包含归属条件。 */
public interface KnowledgeDocumentMapper {
    /** @return 当前用户按创建时间倒序的文档 */
    List<KnowledgeDocumentDO> list(@Param("userId") Long userId);
    /** @return 当前用户文档数量 */
    int count(@Param("userId") Long userId);
    /** @return 当前用户指定文档，不属于当前用户时为空 */
    KnowledgeDocumentDO selectOwned(@Param("id") String id, @Param("userId") Long userId);
    /** @return 写入数量 */
    int insert(KnowledgeDocumentDO document);
    /** 从 INDEXING 发布或记录失败；防止覆盖删除状态。@return 更新数量 */
    int complete(@Param("id") String id, @Param("userId") Long userId,
                 @Param("status") String status, @Param("error") String error, @Param("now") LocalDateTime now);
    /** 原子撤销发布，活动索引不可删除，超时索引允许清理。@return 更新数量 */
    int beginDelete(@Param("id") String id, @Param("userId") Long userId,
                    @Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);
    /** 为待删除文档记录可重试失败原因。@return 更新数量 */
    int deleteFailed(@Param("id") String id, @Param("userId") Long userId, @Param("error") String error,
                     @Param("now") LocalDateTime now);
    /** 为已清理的文档保留删除标记。@return 更新数量 */
    int delete(@Param("id") String id, @Param("userId") Long userId, @Param("now") LocalDateTime now);
}
