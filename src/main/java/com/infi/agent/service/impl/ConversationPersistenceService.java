package com.infi.agent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.entity.ConversationDO;
import com.infi.agent.entity.ConversationTurnDO;
import com.infi.agent.mapper.ConversationMapper;
import com.infi.agent.mapper.ConversationTurnMapper;
import com.infi.agent.memory.ConversationMemoryId;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.vo.ConversationHistoryVO;
import com.infi.agent.vo.ConversationVO;
import com.infi.agent.vo.ConversationTurnVO;
import com.infi.agent.vo.ReactRunVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL作为会话权威来源；模型调用期间仅持有短事务租约，不占用数据库事务。 */
@Service
@RequiredArgsConstructor
public class ConversationPersistenceService {
    private final ConversationMapper conversationMapper;
    private final ConversationTurnMapper turnMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 校验标题及归属，并仅更新会话元数据。
     * @param userId 当前用户
     * @param id 会话编号
     * @param title 新标题，最多80字
     * @return 更新后的会话
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO rename(Long userId, String id, String title) {
        if (title == null || title.isBlank() || title.length() > 80) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TITLE", "会话标题须为1到80字");
        }
        requireOwned(userId, id);
        conversationMapper.rename(id, userId, title.trim());
        return view(requireOwned(userId, id));
    }

    /**
     * 创建仅属于当前用户的会话。
     *
     * @param userId 当前用户
     * @param title 可选标题
     * @return 新会话的公开信息
     */
    public ConversationVO create(Long userId, String title) {
        ConversationDO row = new ConversationDO();
        row.setId(UUID.randomUUID().toString());
        row.setUserId(userId);
        row.setTitle(title == null || title.isBlank() ? "新会话" : title.trim());
        conversationMapper.insert(row);
        return view(requireOwned(userId, row.getId()));
    }

    /**
     * @param userId 当前用户
     * @return 该用户最近50个会话
     */
    public List<ConversationVO> list(Long userId) {
        return conversationMapper.list(userId).stream().map(this::view).toList();
    }

    /**
     * 验证归属，不向其他用户透露会话存在性。
     *
     * @param userId 当前用户
     * @param id 会话ID
     * @return 会话元数据
     */
    public ConversationDO requireOwned(Long userId, String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CONVERSATION", "会话编号不合法");
        }
        ConversationDO row = conversationMapper.selectOwned(id, userId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在");
        }
        return row;
    }

    /**
     * 获取所属会话的持久化记忆。
     *
     * @param id 可信记忆标识
     * @return 含JSON和版本的MySQL快照
     */
    public ConversationDO snapshot(ConversationMemoryId id) {
        requireOwned(id.userId(), id.conversationId());
        ConversationDO row = conversationMapper.selectSnapshot(id.conversationId(), id.userId());
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在");
        }
        return row;
    }

    /**
     * 原子取得会话租约，防止同一会话的并发执行互相覆盖。
     *
     * @param userId 当前用户
     * @param id 会话编号
     * @param leaseDuration 大于整次模型执行时限的租约有效期
     * @return 具有写权限的记忆标识
     */
    public ConversationMemoryId claim(Long userId, String id, Duration leaseDuration) {
        requireOwned(userId, id);
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(clock);
        if (conversationMapper.claim(id, userId, token, now, now.plus(leaseDuration)) != 1) {
            throw busy();
        }
        return new ConversationMemoryId(userId, id, token);
    }

    /**
     * @param id 当前请求持有的租约，释放时不能删除他人的新租约
     */
    public void release(ConversationMemoryId id) {
        conversationMapper.release(id.conversationId(), id.userId(), id.leaseToken());
    }

    /**
     * 原子更新有界记忆；必须具有未过期的租约。
     *
     * @param id 服务端记忆标识
     * @param json LangChain4j序列化后的消息
     * @return 更新后的版本及内容
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationDO updateSnapshot(ConversationMemoryId id, String json) {
        if (id.leaseToken() == null || conversationMapper.updateSnapshot(id.conversationId(), id.userId(),
                id.leaseToken(), LocalDateTime.now(clock), json) != 1) {
            throw busy();
        }
        return snapshot(id);
    }

    /**
     * 在同一短事务中写完整轮次与成功对话的窗口，失败轮次不进入模型记忆。
     *
     * @param id 当前租约
     * @param mode 执行模式
     * @param goal 当前输入
     * @param result 实际执行结果
     * @param memory 当前会话的LangChain4j记忆
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(ConversationMemoryId id, ConversationMode mode, String goal, ReactRunVO result, ChatMemory memory) {
        ConversationTurnDO turn = new ConversationTurnDO();
        turn.setConversationId(id.conversationId());
        turn.setMode(mode.name());
        turn.setGoal(goal.trim());
        turn.setAnswer(result.answer());
        turn.setStatus(result.status());
        try {
            turn.setResultJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("执行结果无法序列化", exception);
        }
        if ("SUCCEEDED".equals(result.status())) {
            List<ChatMessage> messages = new ArrayList<>(memory.messages());
            messages.add(UserMessage.from(goal.trim()));
            messages.add(AiMessage.from(result.answer()));
            memory.set(messages);
        }
        if (turnMapper.insert(turn, id.userId(), id.leaseToken(), LocalDateTime.now(clock)) != 1) {
            throw busy();
        }
    }

    /**
     * 按游标读取最近轮次并转换为时间顺序。
     *
     * @param userId 当前用户
     * @param id 会话编号
     * @param beforeId 可选的更早记录游标
     * @return 最多50条记录及下一页游标
     */
    public ConversationHistoryVO history(Long userId, String id, Long beforeId) {
        requireOwned(userId, id);
        if (beforeId != null && beforeId < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "历史游标不合法");
        }
        List<ConversationTurnDO> rows = new ArrayList<>(turnMapper.history(id, userId, beforeId));
        boolean more = rows.size() > 50;
        if (more) {
            rows.remove(rows.size() - 1);
        }
        Long next = more ? rows.get(rows.size() - 1).getId() : null;
        Collections.reverse(rows);
        return new ConversationHistoryVO(rows.stream().map(row -> new ConversationTurnVO(row.getId(),
                row.getMode(), row.getGoal(), row.getAnswer(), row.getStatus(), row.getGmtCreate())).toList(), next);
    }

    /**
     * 原子清空记忆与完整历史，可选择一并删除会话。
     *
     * @param id 有效租约
     * @param memory 由RedisChatMemoryStore支持的记忆
     * @param deleteConversation 是否删除会话本身
     */
    @Transactional(rollbackFor = Exception.class)
    public void clear(ConversationMemoryId id, ChatMemory memory, boolean deleteConversation) {
        memory.clear();
        turnMapper.delete(id.conversationId());
        if (deleteConversation && conversationMapper.delete(id.conversationId(), id.userId(),
                id.leaseToken(), LocalDateTime.now(clock)) != 1) {
            throw busy();
        }
    }

    /**
     * @param row 会话数据库对象
     * @return 不含租约和记忆正文的公开对象
     */
    private ConversationVO view(ConversationDO row) {
        return new ConversationVO(row.getId(), row.getTitle(), row.getGmtCreate(), row.getGmtModified());
    }

    /** @return 不包含私人会话内容的并发冲突异常 */
    private BusinessException busy() {
        return new BusinessException(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "该会话正在执行或租约已过期，请稍后重试");
    }
}
