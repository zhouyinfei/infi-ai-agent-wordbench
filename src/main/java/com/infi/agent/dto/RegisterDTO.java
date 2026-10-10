package com.infi.agent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 注册或管理员创建普通用户的输入，不接受角色字段。 */
public record RegisterDTO(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{3,31}$", message = "用户名须以字母开头，由4至32位字母、数字或下划线组成")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度须为8至64位")
        @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "密码须使用可见ASCII字符，不含空格")
        String password,
        @NotBlank(message = "昵称不能为空")
        @Size(max = 50, message = "昵称不能超过50字") String nickname,
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱不能超过100字") String email) {
}
