package com.infi.agent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 个人资料输入，不允许修改身份或角色。 */
public record ProfileDTO(
        @NotBlank(message = "昵称不能为空") @Size(max = 50, message = "昵称不能超过50字") String nickname,
        @Email(message = "邮箱格式不正确") @Size(max = 100, message = "邮箱不能超过100字") String email) {
}
