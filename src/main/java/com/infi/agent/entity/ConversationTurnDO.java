package com.infi.agent.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 完整执行轮次，成功与失败结果均保留，失败不会进入模型公共记忆。 */
@Data
public class ConversationTurnDO {
    private Long id;
    private String conversationId;
    private String mode;
    private String goal;
    private String answer;
    private String status;
    private String resultJson;
    private LocalDateTime gmtCreate;
}
