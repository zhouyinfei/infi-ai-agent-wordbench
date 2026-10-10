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

    /**
     * 查询当前登录用户的资料，用户身份来自认证上下文。
     *
     * @param principal 认证上下文中的当前登录用户
     * @return 操作结果
     */
    @GetMapping
    public ApiResponse<UserVO> profile(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(userService.getProfile(principal.id()));
    }

    /**
     * 更新当前登录用户的昵称和邮箱，并返回更新后的资料。
     *
     * @param principal 认证上下文中的当前登录用户
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @PutMapping
    public ApiResponse<UserVO> update(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody ProfileDTO request) {
        return ApiResponse.success(userService.updateProfile(principal.id(), request));
    }

    /**
     * 校验原密码并修改当前用户密码；凭证版本更新后旧会话将失效。
     *
     * @param principal 认证上下文中的当前登录用户
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @PutMapping("/password")
    public ApiResponse<Void> password(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody PasswordDTO request) {
        userService.changePassword(principal.id(), request);
        return ApiResponse.success(null);
    }
}
