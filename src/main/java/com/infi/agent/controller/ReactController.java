package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.ReactService;
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

    @GetMapping("/config")
    public ApiResponse<ReactConfigVO> configuration() {
        return ApiResponse.success(reactService.configuration());
    }

    @PostMapping("/runs")
    public ApiResponse<ReactRunVO> run(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody ReactRunDTO request) {
        return ApiResponse.success(reactService.execute(principal.id(), request));
    }
}
