package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.AgentTaskDecisionDTO;
import com.infi.agent.dto.AgentTaskSubmitDTO;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.AgentTaskService;
import com.infi.agent.vo.AgentTaskVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 正常工作台执行入口：模型提出人工检查点，当前任务用户本人决定。 */
@RestController
@RequestMapping("/api/agent-tasks")
@RequiredArgsConstructor
public class AgentTaskController {
    private final AgentTaskService taskService;

    /**
     * 提交当前用户的智能体任务，返回可供轮询的执行快照。
     *
     * @param user 当前用户
     * @param request 已校验的任务
     * @return 可轮询的执行快照
     */
    @PostMapping
    public ApiResponse<AgentTaskVO> submit(@AuthenticationPrincipal UserPrincipal user,
                                           @Valid @RequestBody AgentTaskSubmitDTO request) {
        return ApiResponse.success(taskService.submit(user, request));
    }

    /**
     * 根据任务编号查询当前用户本人的任务执行快照。
     *
     * @param user 当前用户
     * @param id 任务编号
     * @return 本人任务快照
     */
    @GetMapping("/{id}")
    public ApiResponse<AgentTaskVO> get(@AuthenticationPrincipal UserPrincipal user, @PathVariable String id) {
        return ApiResponse.success(taskService.get(user, id));
    }

    /**
     * 查询当前用户所有尚未结束的任务。
     *
     * @param user 当前用户
     * @return 本人当前未结束任务
     */
    @GetMapping
    public ApiResponse<List<AgentTaskVO>> active(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(taskService.active(user));
    }

    /**
     * 提交当前用户对任务中具体操作的人工决定，返回最新执行快照。
     *
     * @param user 当前用户
     * @param id 任务编号
     * @param decision 针对具体操作的决定
     * @return 最新执行快照
     */
    @PostMapping("/{id}/decision")
    public ApiResponse<AgentTaskVO> decide(@AuthenticationPrincipal UserPrincipal user, @PathVariable String id,
                                           @Valid @RequestBody AgentTaskDecisionDTO decision) {
        return ApiResponse.success(taskService.decide(user, id, decision));
    }
}
