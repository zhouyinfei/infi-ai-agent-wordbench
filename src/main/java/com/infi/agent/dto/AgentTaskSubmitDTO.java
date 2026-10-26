package com.infi.agent.dto;

import com.infi.agent.memory.ConversationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 工作台任务输入，用户身份由认证上下文决定。 */
public record AgentTaskSubmitDTO(@NotBlank @Size(max = 2000) String goal,
                                  @NotNull ConversationMode mode,
                                  @Pattern(regexp = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") String conversationId,
                                  boolean useKnowledge, boolean useNetwork,
                                  @Size(max = 80) String modelId) {
}
