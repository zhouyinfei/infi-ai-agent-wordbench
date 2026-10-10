package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.PasswordDTO;
import com.infi.agent.dto.ProfileDTO;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.UserService;
import com.infi.agent.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户接口，用户ID只能来自已验证身份。 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping
    public ApiResponse<UserVO> profile(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(userService.getProfile(principal.id()));
    }

    @PutMapping
    public ApiResponse<UserVO> update(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody ProfileDTO request) {
        return ApiResponse.success(userService.updateProfile(principal.id(), request));
    }

    @PutMapping("/password")
    public ApiResponse<Void> password(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody PasswordDTO request) {
        userService.changePassword(principal.id(), request);
        return ApiResponse.success(null);
    }
}
