package com.infi.agent.vo;

/** 登录结果；expiresIn 单位为秒。 */
public record LoginVO(String token, long expiresIn, UserVO user) {
}
