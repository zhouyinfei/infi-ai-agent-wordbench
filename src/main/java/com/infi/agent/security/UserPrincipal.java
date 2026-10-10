package com.infi.agent.security;

/** 已验证的身份，不包含凭证。 */
public record UserPrincipal(Long id, String username, String role) {
}
