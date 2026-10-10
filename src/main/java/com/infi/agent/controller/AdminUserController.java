package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.dto.UserStatusDTO;
import com.infi.agent.service.UserService;
import com.infi.agent.vo.PageVO;
import com.infi.agent.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员用户管理，访问权限由 SecurityConfig 统一约束。 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final UserService userService;

    @GetMapping
    public ApiResponse<PageVO<UserVO>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) String keyword) {
        return ApiResponse.success(userService.listUsers(page, size, keyword));
    }

    @PostMapping
    public ApiResponse<UserVO> create(@Valid @RequestBody RegisterDTO request) {
        return ApiResponse.success(userService.register(request));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @Valid @RequestBody UserStatusDTO request) {
        userService.updateStatus(id, request.status());
        return ApiResponse.success(null);
    }
}
