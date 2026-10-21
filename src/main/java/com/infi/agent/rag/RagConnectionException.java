package com.infi.agent.rag;

import java.io.IOException;

/** 可向客户端展示的连接错误，不包含上游响应、地址或凭证。 */
public class RagConnectionException extends IOException {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    public RagConnectionException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public RagConnectionException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public RagConnectionException(String message) {
        this(message, null, true);
    }

    public RagConnectionException(String message, boolean retryable) {
        this(message, null, retryable);
    }

    /** @return 换词或短时重试是否可能恢复；认证、额度和配置错误返回 false */
    public boolean isRetryable() {
        return retryable;
    }
}
