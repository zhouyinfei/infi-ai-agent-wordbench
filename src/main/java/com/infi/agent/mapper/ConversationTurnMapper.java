package com.infi.agent.mapper;

import com.infi.agent.entity.ConversationTurnDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 完整轮次的数据访问，写入必须持有该用户会话的有效租约。 */
public interface ConversationTurnMapper {
    /**
     * @param turn 完整轮次
     * @param userId 所属用户
     * @param token 有效租约
     * @param now 当前时间
     * @return 写入数量
     */
    int insert(@Param("turn") ConversationTurnDO turn, @Param("userId") Long userId,
               @Param("token") String token, @Param("now") LocalDateTime now);
    /**
     * @param id 所属会话
     * @param userId 所属用户
     * @param beforeId 可选游标
     * @return 最近51条轮次，用于分页
     */
    List<ConversationTurnDO> history(@Param("id") String id, @Param("userId") Long userId, @Param("beforeId") Long beforeId);
    /**
     * @param id 会话ID
     * @return 清除数量，调用者需先锁定所属会话
     */
    int delete(@Param("id") String id);
}
