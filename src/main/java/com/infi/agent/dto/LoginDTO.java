package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录输入。 */
public record LoginDTO(
        @NotBlank(message = "用户名不能为空") @Size(max = 32) String username,
        @NotBlank(message = "密码不能为空") @Size(max = 64) String password) {
}
