package com.infi.agent.vo;

import java.util.List;

/** 最近轮次的时间顺序页面，nextBeforeId可用于获取更早记录。 */
public record ConversationHistoryVO(List<ConversationTurnVO> records, Long nextBeforeId) {
}
