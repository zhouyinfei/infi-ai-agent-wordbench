package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.react.model.ModelSelection;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.ReactService;
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

/** 已登录用户的 ReAct 配置与同步执行入口。 */
@RestController
@RequestMapping("/api/react")
@RequiredArgsConstructor
public class ReactController {
    private final ReactService reactService;
    private final ConversationService conversationService;

    /**
     * 返回模型是否配置、可用工具及 ReAct 执行限制，不包含模型密钥。
     *
     * @return 模型、工具与执行预算配置
     */
    @GetMapping("/config")
    public ApiResponse<ReactConfigVO> configuration() {
        return ApiResponse.success(reactService.configuration());
    }

    /**
     * 以当前登录用户身份同步执行任务，返回最终结果及模型和工具调用轨迹。
     *
     * @param principal 认证上下文中的当前登录用户
     * @param request 已通过接口参数校验的请求数据
     * @return 任务执行结果及轨迹
     */
    @PostMapping("/runs")
    public ApiResponse<ReactRunVO> run(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody ReactRunDTO request) {
        try (var selection = ModelSelection.select(request.modelId())) {
            if (request.conversationId() != null) {
                return ApiResponse.success(conversationService.execute(principal.id(), request.conversationId(),
                        ConversationMode.REACT, request.goal(), request.useKnowledge(), request.useNetwork()));
            }
            return ApiResponse.success(reactService.execute(principal.id(), request));
        }
    }
}
