package com.infi.agent.multi;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具真实观察，不接受模型自行声称已经执行的结果。 */
public record AgentEvidence(String toolName, JsonNode arguments, String result, boolean success) {
    /** 隔离JSON参数节点，防止不同Agent通过参数引用影响他人证据。 */
    public AgentEvidence {
        arguments = arguments == null ? null : arguments.deepCopy();
    }

    /** @return 工具参数的独立副本 */
    @Override
    public JsonNode arguments() {
        return arguments == null ? null : arguments.deepCopy();
    }
}
