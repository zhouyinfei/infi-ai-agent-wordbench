package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** 提供模型协议，注册表在编排线程处理暂停和精确执行，避免占用工具池等待。 */
public class RequestApprovalTool implements ReactTool {
    @Override
    public String execute(JsonNode arguments) {
        throw new IllegalStateException("人工确认必须通过任务编排入口执行");
    }

    /**
     * @param reason 公开理由
     * @param toolName 目标工具
     * @param toolArguments 完整参数
     * @return 协议占位
     */
    @Tool(name = "request_approval", value = "当你判断下一项操作需要当前用户审批时调用；批准后服务端执行指定工具，拒绝后停止任务。")
    public String request(@P("需要用户确认的原因和影响") String reason,
                          @P("当前提供的目标工具名称，禁止request_approval自身") String toolName,
                          @P("目标工具完整参数的JSON对象字符串") String toolArguments) {
        throw new IllegalStateException("人工确认必须通过任务编排入口执行");
    }
}
