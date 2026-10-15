package com.infi.agent.mapper;

import com.infi.agent.entity.ConversationDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 用户归属、有限记忆快照及短事务租约的数据访问。 */
public interface ConversationMapper {
    /** 修改所属会话标题，不修改记忆及租约。
     * @param id 会话编号
     * @param userId 当前用户
     * @param title 新标题
     * @return 更新数量
     */
    int rename(@Param("id") String id, @Param("userId") Long userId, @Param("title") String title);
    /**
     * @param row 新会话
     * @return 写入数量
     */
    int insert(ConversationDO row);
    /**
     * @param userId 当前用户
     * @return 最近50个会话
     */
    List<ConversationDO> list(@Param("userId") Long userId);
    /**
     * @param id 会话ID
     * @param userId 当前用户
     * @return 所属会话元数据，不含记忆正文
     */
    ConversationDO selectOwned(@Param("id") String id, @Param("userId") Long userId);
    /**
     * @param id 会话ID
     * @param userId 当前用户
     * @return 当前记忆正文及版本
     */
    ConversationDO selectSnapshot(@Param("id") String id, @Param("userId") Long userId);
    /**
     * @param id 会话ID
     * @param userId 所属用户
     * @param token 租约令牌
     * @param now 当前时间
     * @param deadline 租约截止时间
     * @return 是否取得租约
     */
    int claim(@Param("id") String id, @Param("userId") Long userId, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("deadline") LocalDateTime deadline);
    /**
     * @param id 会话ID
     * @param userId 所属用户
     * @param token 当前租约
     * @param now 当前时间
     * @param json 记忆JSON
     * @return 更新数量
     */
    int updateSnapshot(@Param("id") String id, @Param("userId") Long userId, @Param("token") String token,
                       @Param("now") LocalDateTime now, @Param("json") String json);
    /**
     * @param id 会话ID
     * @param userId 所属用户
     * @param token 当前租约
     * @return 释放数量
     */
    int release(@Param("id") String id, @Param("userId") Long userId, @Param("token") String token);
    /**
     * @param id 会话ID
     * @param userId 所属用户
     * @param token 当前租约
     * @param now 当前时间
     * @return 删除数量
     */
    int delete(@Param("id") String id, @Param("userId") Long userId, @Param("token") String token,
               @Param("now") LocalDateTime now);
}
