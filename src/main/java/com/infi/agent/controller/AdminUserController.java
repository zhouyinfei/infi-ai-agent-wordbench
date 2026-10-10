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

    /**
     * 分页查询用户，支持按关键字筛选；访问权限由管理员接口的安全配置约束。
     *
     * @param page 从 1 开始的页码
     * @param size 每页记录数
     * @param keyword 可选的用户搜索关键字
     * @return 用户分页记录和总数
     */
    @GetMapping
    public ApiResponse<PageVO<UserVO>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) String keyword) {
        return ApiResponse.success(userService.listUsers(page, size, keyword));
    }

    /**
     * 创建普通用户，复用注册流程，不接受客户端指定管理员角色。
     *
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @PostMapping
    public ApiResponse<UserVO> create(@Valid @RequestBody RegisterDTO request) {
        return ApiResponse.success(userService.register(request));
    }

    /**
     * 更新指定普通用户的启用状态，管理员账号由业务层保护。
     *
     * @param id 待查询或更新的用户 ID
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @Valid @RequestBody UserStatusDTO request) {
        userService.updateStatus(id, request.status());
        return ApiResponse.success(null);
    }
}
