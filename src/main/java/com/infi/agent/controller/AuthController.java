package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.LoginDTO;
import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.service.SessionService;
import com.infi.agent.service.UserService;
import com.infi.agent.vo.LoginVO;
import com.infi.agent.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口，只负责输入和输出转换。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final SessionService sessionService;

    /**
     * 注册普通用户，校验请求后返回不含密码的用户资料。
     *
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @PostMapping("/register")
    public ApiResponse<UserVO> register(@Valid @RequestBody RegisterDTO request) {
        return ApiResponse.success(userService.register(request));
    }

    /**
     * 使用账号密码登录，将请求来源地址传入业务层进行登录限流。
     *
     * @param request 已通过接口参数校验的请求数据
     * @param servletRequest HTTP 请求，用于获取登录来源地址
     * @return 登录令牌、有效期和用户资料
     */
    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@Valid @RequestBody LoginDTO request, HttpServletRequest servletRequest) {
        return ApiResponse.success(userService.login(request, servletRequest.getRemoteAddr()));
    }

    /**
     * 从已认证的 Bearer 请求头中提取令牌并撤销当前会话。
     *
     * @param authorization 已通过认证过滤器校验的 Bearer 认证头
     * @return 操作结果
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        sessionService.revoke(authorization.substring(7));
        return ApiResponse.success(null);
    }
}
