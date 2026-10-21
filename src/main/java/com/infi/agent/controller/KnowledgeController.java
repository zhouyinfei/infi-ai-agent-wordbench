package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.KnowledgeService;
import com.infi.agent.vo.KnowledgeDocumentVO;
import com.infi.agent.vo.KnowledgeHitVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 知识库接口，用户身份仅从已认证上下文读取。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;

    /** 返回当前用户的文档列表。 */
    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentVO>> list(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(service.list(user.id()));
    }

    /** 接收受大小限制的单文件并同步入库。 */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDocumentVO> upload(@AuthenticationPrincipal UserPrincipal user,
                                                  @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(service.upload(user.id(), file));
    }

    /** 下载鉴权后的原文，强制附件下载并禁止缓存。 */
    @GetMapping("/documents/{id}/content")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal UserPrincipal user, @PathVariable String id) {
        KnowledgeDocumentVO document = service.document(user.id(), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.download(user.id(), id));
    }

    /** 删除当前用户文档及其所有存储内容。 */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable String id) {
        service.delete(user.id(), id);
        return ApiResponse.success(null);
    }

    /** 语义检索仅在当前用户已发布文档范围内执行。 */
    @PostMapping("/search")
    public ApiResponse<List<KnowledgeHitVO>> search(@AuthenticationPrincipal UserPrincipal user,
                                                   @Valid @RequestBody SearchRequest request) {
        return ApiResponse.success(service.search(user.id(), request.query(), request.limit()));
    }

    /** 有界检索请求，不提供可覆盖认证身份的字段。 */
    public record SearchRequest(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(10) int limit) {
    }
}
