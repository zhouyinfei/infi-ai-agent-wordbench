package com.infi.agent.service.impl;

import com.infi.agent.approval.HumanApprovalContext;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.ChatMemoryProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.config.ReactProperties;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.memory.ConversationMemoryId;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.memory.RedisChatMemoryStore;
import com.infi.agent.service.ConversationService;
import com.infi.agent.service.MultiAgentService;
import com.infi.agent.service.PlanExecuteService;
import com.infi.agent.service.ReactService;
import com.infi.agent.vo.ConversationHistoryVO;
import com.infi.agent.vo.ConversationVO;
import com.infi.agent.vo.ReactRunVO;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 三种模式统一复用LangChain4j会话窗口，数据库事务不包含模型调用。 */
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {
    private final ConversationPersistenceService persistence;
    private final RedisChatMemoryStore memoryStore;
    private final ChatMemoryProperties memoryProperties;
    private final ReactProperties reactProperties;
    private final MultiAgentProperties multiProperties;
    private final ReactService reactService;
    private final PlanExecuteService planService;
    private final MultiAgentService multiService;

    /**
     * @param userId 当前用户
     * @param title 可选标题
     * @return 新会话
     */
    @Override
    public ConversationVO create(Long userId, String title) {
        return persistence.create(userId, title);
    }

    /** 修改标题，并由持久化服务检查会话归属。
     * @param userId 当前用户
     * @param id 会话编号
     * @param title 新标题
     * @return 更新后的会话
     */
    @Override
    public ConversationVO rename(Long userId, String id, String title) {
        return persistence.rename(userId, id, title);
    }

    /**
     * @param userId 当前用户
     * @return 最近50个会话
     */
    @Override
    public List<ConversationVO> list(Long userId) {
        return persistence.list(userId);
    }

    /**
     * @param userId 当前用户
     * @param id 会话编号
     * @param beforeId 可选游标
     * @return 完整历史页面
     */
    @Override
    public ConversationHistoryVO history(Long userId, String id, Long beforeId) {
        return persistence.history(userId, id, beforeId);
    }

    /**
     * 使用所属会话的近期消息执行目标，成功轮次入记忆，所有执行结果保存完整历史。
     *
     * @param userId 当前用户
     * @param id 会话编号
     * @param mode 已支持执行模式
     * @param goal 当前目标
     * @return 原执行结果和真实Token统计
     */
    @Override
    public ReactRunVO execute(Long userId, String id, ConversationMode mode, String goal) {
        return execute(userId, id, mode, goal, false);
    }

    /** 用户明确选择的知识库权限仅用于本次任务，不从历史消息继承。 */
    @Override
    public ReactRunVO execute(Long userId, String id, ConversationMode mode, String goal, boolean useKnowledge) {
        return execute(userId, id, mode, goal, useKnowledge, false);
    }

    /** 当前轮次分别授权知识库和联网，历史消息不能自动开启工具。 */
    @Override
    public ReactRunVO execute(Long userId, String id, ConversationMode mode, String goal,
                              boolean useKnowledge, boolean useNetwork) {
        ConversationMemoryId memoryId = claim(userId, id);
        try {
            ChatMemory memory = window(memoryId);
            List<ChatMessage> history = memory.messages();
            ReactRunVO result = switch (mode) {
                case REACT -> reactService.execute(userId, new ReactRunDTO(goal, null, useKnowledge, useNetwork), history);
                case PLAN_EXECUTE -> planService.execute(userId, new PlanExecuteRunDTO(goal, null, useKnowledge, useNetwork), history);
                case MULTI_AGENT -> multiService.execute(userId, new MultiAgentRunDTO(goal, null, useKnowledge, useNetwork), history);
            };
            persistence.complete(memoryId, mode, goal, result, memory);
            return result;
        } finally {
            persistence.release(memoryId);
        }
    }

    /**
     * @param userId 当前用户
     * @param id 会话编号 清空历史与窗口
     */
    @Override
    public void clear(Long userId, String id) {
        clearInternal(userId, id, false);
    }

    /**
     * @param userId 当前用户
     * @param id 会话编号 删除会话与历史
     */
    @Override
    public void delete(Long userId, String id) {
        clearInternal(userId, id, true);
    }

    /**
     * @param userId 当前用户
     * @param id 所属会话
     * @param delete 是否删除会话
     */
    private void clearInternal(Long userId, String id, boolean delete) {
        ConversationMemoryId memoryId = claim(userId, id);
        try {
            persistence.clear(memoryId, window(memoryId), delete);
        } finally {
            persistence.release(memoryId);
        }
    }

    /**
     * @param memoryId 所属会话及写租约
     * @return 由RedisChatMemoryStore支持的标准消息窗口
     */
    private ChatMemory window(ConversationMemoryId memoryId) {
        return MessageWindowChatMemory.builder().id(memoryId).maxMessages(memoryProperties.getMaxMessages())
                .chatMemoryStore(memoryStore).build();
    }

    /**
     * @param userId 当前用户
     * @param id 所属会话
     * @return 足以覆盖整次任务的有效租约
     */
    private ConversationMemoryId claim(Long userId, String id) {
        if (reactProperties.getTaskTimeout() == null || multiProperties.getTaskTimeout() == null
                || reactProperties.getTaskTimeout().isNegative() || reactProperties.getTaskTimeout().isZero()
                || multiProperties.getTaskTimeout().isNegative() || multiProperties.getTaskTimeout().isZero()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_MEMORY_CONFIG", "会话执行时限配置不合法");
        }
        Duration timeout = reactProperties.getTaskTimeout().compareTo(multiProperties.getTaskTimeout()) > 0
                ? reactProperties.getTaskTimeout() : multiProperties.getTaskTimeout();
        Duration approvalWait = HumanApprovalContext.active() ? HumanApprovalContext.MAX_TOTAL_WAIT : Duration.ZERO;
        return persistence.claim(userId, id, timeout.plusSeconds(60).plus(approvalWait));
    }
}
