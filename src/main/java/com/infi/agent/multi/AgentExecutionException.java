package com.infi.agent.multi;

/** 可预期的预算或执行终止，只保存可公开的错误信息。 */
public class AgentExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String status;

    /**
     * 创建执行终止信号。
     *
     * @param status 结束状态
     * @param reason 公开停止原因
     */
    public AgentExecutionException(String status, String reason) {
        super(reason);
        this.status = status;
    }

    /** @return 执行停止状态 */
    public String status() {
        return status;
    }
}
