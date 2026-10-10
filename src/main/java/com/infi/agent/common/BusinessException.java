package com.infi.agent.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 可向客户端展示的业务异常，不包含凭证。 */
@Getter
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
