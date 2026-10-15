package com.infi.agent.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 会话持久化对象；memoryJson为有限窗口，不等于完整历史。 */
@Data
public class ConversationDO {
    private String id;
    private Long userId;
    private String title;
    private Long memoryVersion;
    private String memoryJson;
    private String lockToken;
    private LocalDateTime lockDeadline;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
