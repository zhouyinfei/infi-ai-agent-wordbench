package com.infi.agent.service;

import com.infi.agent.memory.ConversationMode;
import com.infi.agent.vo.ConversationHistoryVO;
import com.infi.agent.vo.ConversationVO;
import com.infi.agent.vo.ReactRunVO;
import java.util.List;

/** 用户会话生命周期及持久化记忆执行边界。 */
public interface ConversationService {
    /**
     * @param userId 当前用户
     * @param title 可选标题
     * @return 新会话
     */
    ConversationVO create(Long userId, String title);
    /** 修改所属会话的标题。
     * @param userId 当前用户
     * @param id 会话编号
     * @param title 新标题
     * @return 更新后的会话
     */
    ConversationVO rename(Long userId, String id, String title);
    /**
     * @param userId 当前用户
     * @return 该用户最近会话
     */
    List<ConversationVO> list(Long userId);
    /**
     * @param userId 当前用户
     * @param id 会话编号
     * @param beforeId 可选游标
     * @return 完整轮次的历史页
     */
    ConversationHistoryVO history(Long userId, String id, Long beforeId);
    /**
     * @param userId 当前用户
     * @param id 会话编号 清空历史与模型记忆
     */
    void clear(Long userId, String id);
    /**
     * @param userId 当前用户
     * @param id 会话编号 删除会话、历史和模型记忆
     */
    void delete(Long userId, String id);
    /**
     * @param userId 当前用户
     * @param id 会话编号
     * @param mode 执行模式
     * @param goal 任务目标
     * @return 包含记忆的执行结果
     */
    ReactRunVO execute(Long userId, String id, ConversationMode mode, String goal);
}
