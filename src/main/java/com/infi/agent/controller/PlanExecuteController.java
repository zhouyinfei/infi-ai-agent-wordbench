package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.react.model.ModelSelection;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.PlanExecuteService;
import com.infi.agent.service.ConversationService;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.vo.ReactRunVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录用户的 Plan-and-Execute 同步执行入口。 */
@RestController
@RequestMapping("/api/plan-execute")
@RequiredArgsConstructor
public class PlanExecuteController {
    private final PlanExecuteService planExecuteService;
    private final ConversationService conversationService;

    /**
     * 使用当前用户身份执行计划任务，不接受客户端指定用户或工具范围。
     *
     * @param principal 已认证用户
     * @param request 已通过校验的任务目标
     * @return 统一响应中的执行结果及轨迹
     */
    @PostMapping("/runs")
    public ApiResponse<ReactRunVO> run(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody PlanExecuteRunDTO request) {
        try (var selection = ModelSelection.select(request.modelId())) {
            if (request.conversationId() != null) {
                return ApiResponse.success(conversationService.execute(principal.id(), request.conversationId(),
                        ConversationMode.PLAN_EXECUTE, request.goal(), request.useKnowledge(), request.useNetwork()));
            }
            return ApiResponse.success(planExecuteService.execute(principal.id(), request));
        }
    }
}
