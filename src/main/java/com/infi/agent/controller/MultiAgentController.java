package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.react.model.ModelSelection;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.MultiAgentService;
import com.infi.agent.service.ConversationService;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.vo.ReactConfigVO;
import com.infi.agent.vo.ReactRunVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录用户的协作配置和执行入口，权限沿用Bearer认证。 */
@RestController
@RequestMapping("/api/multi-agent")
@RequiredArgsConstructor
public class MultiAgentController {
    private final MultiAgentService multiAgentService;
    private final ConversationService conversationService;

    /** @return 不含模型凭证的公开协作配置 */
    @GetMapping("/config")
    public ApiResponse<ReactConfigVO> configuration() {
        return ApiResponse.success(multiAgentService.configuration());
    }

    /**
     * 使用已认证的用户身份执行协作任务。
     *
     * @param principal 当前用户身份，禁止从请求体读取用户 ID
     * @param request 经校验的目标
     * @return 分工、专业结果、工具证据和独立审校结论
     */
    @PostMapping("/runs")
    public ApiResponse<ReactRunVO> run(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody MultiAgentRunDTO request) {
        try (var selection = ModelSelection.select(request.modelId())) {
            if (request.conversationId() != null) {
                return ApiResponse.success(conversationService.execute(principal.id(), request.conversationId(),
                        ConversationMode.MULTI_AGENT, request.goal(), request.useKnowledge(), request.useNetwork()));
            }
            return ApiResponse.success(multiAgentService.execute(principal.id(), request));
        }
    }
}
