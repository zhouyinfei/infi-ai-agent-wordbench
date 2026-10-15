package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.dto.CreateConversationDTO;
import com.infi.agent.dto.RenameConversationDTO;
import org.springframework.web.bind.annotation.PatchMapping;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.ConversationService;
import com.infi.agent.vo.ConversationHistoryVO;
import com.infi.agent.vo.ConversationVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会话接口仅使用已认证用户身份，禁止查看或修改其他用户的会话。 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversationService;

    /**
     * @param principal 当前用户
     * @param request 可选标题
     * @return 新会话
     */
    @PostMapping
    public ApiResponse<ConversationVO> create(@AuthenticationPrincipal UserPrincipal principal,
                                              @Valid @RequestBody CreateConversationDTO request) {
        return ApiResponse.success(conversationService.create(principal.id(), request.title()));
    }

    /**
     * @param principal 当前用户
     * @return 最近50个所属会话
     */
    /** 修改当前用户所属会话的标题，不改变历史和记忆。
     * @param principal 当前用户
     * @param id 会话编号
     * @param request 新标题
     * @return 更新后的会话
     */
    @PatchMapping("/{id}")
    public ApiResponse<ConversationVO> rename(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable String id,
                                              @Valid @RequestBody RenameConversationDTO request) {
        return ApiResponse.success(conversationService.rename(principal.id(), id, request.title()));
    }

    @GetMapping
    public ApiResponse<List<ConversationVO>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(conversationService.list(principal.id()));
    }

    /**
     * @param principal 当前用户
     * @param id 会话ID
     * @param beforeId 可选历史游标
     * @return 完整轮次页
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<ConversationHistoryVO> history(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable String id,
                                                      @RequestParam(required = false) Long beforeId) {
        return ApiResponse.success(conversationService.history(principal.id(), id, beforeId));
    }

    /**
     * @param principal 当前用户
     * @param id 会话ID
     * @return 清空历史及记忆的结果
     */
    @DeleteMapping("/{id}/messages")
    public ApiResponse<Void> clear(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String id) {
        conversationService.clear(principal.id(), id);
        return ApiResponse.success(null);
    }

    /**
     * @param principal 当前用户
     * @param id 会话ID
     * @return 删除会话及其历史的结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String id) {
        conversationService.delete(principal.id(), id);
        return ApiResponse.success(null);
    }
}
