package com.infi.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 用户启停状态。 */
public record UserStatusDTO(@NotNull @Min(0) @Max(1) Integer status) {
}
