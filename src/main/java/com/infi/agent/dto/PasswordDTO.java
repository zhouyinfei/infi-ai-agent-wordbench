package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 修改密码输入。 */
public record PasswordDTO(
        @NotBlank(message = "原密码不能为空") @Size(max = 64) String oldPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度须为8至64位")
        @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "密码须使用可见ASCII字符，不含空格")
        String newPassword) {
}
