package com.example.receiptmemo.notion.controller;

import com.example.receiptmemo.global.config.NotionConfig;
import com.example.receiptmemo.ledger.dto.AddReceiptsToPageResponse;
import com.example.receiptmemo.ledger.service.ReceiptPageService;
import com.example.receiptmemo.notion.service.NotionService;
import com.example.receiptmemo.notion.service.ReceiptAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notion 댓글 디버그 / 수동 처리 엔드포인트.
 *  - GET  /api/notion/comments/debug   : Notion API raw 댓글 JSON 반환 (debug-comments=true 일 때만)
 *  - POST /api/notion/comments/process : pageId 의 댓글 첨부를 다운로드해 영수증 처리
 */
@Slf4j
@Tag(name = "NotionCommentDebug", description = "Notion 댓글 디버그/수동 처리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notion/comments")
public class NotionCommentDebugController {

    private final NotionService notionService;
    private final NotionConfig notionConfig;
    private final ReceiptAttachmentService attachmentService;
    private final ReceiptPageService receiptPageService;

    @Operation(summary = "[Debug] Notion 페이지의 raw 댓글 JSON 조회")
    @GetMapping(value = "/debug", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> debug(@RequestParam String pageId) {
        if (!notionConfig.isDebugComments()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "forbidden",
                    "message", "comments debug endpoint is disabled. set NOTION_WEBHOOK_DEBUG_COMMENTS=true to enable."
            ));
        }
        String raw = notionService.listCommentsRaw(pageId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(raw);
    }

    @Operation(summary = "Notion 페이지 댓글의 이미지 첨부를 수동으로 처리")
    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestParam String pageId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", pageId);

        String rawJson;
        try {
            rawJson = notionService.listCommentsRaw(pageId);
        } catch (Exception e) {
            log.error("[CommentsProcess] 댓글 조회 실패. pageId={}, err={}", pageId, e.getMessage());
            result.put("ok", false);
            result.put("stage", "listCommentsRaw");
            result.put("error", e.getMessage());
            return ResponseEntity.status(502).body(result);
        }

        List<ReceiptAttachmentService.ImageRef> refs =
                attachmentService.extractImageAttachmentsFromRaw(rawJson);
        result.put("imageRefCount", refs.size());

        List<MultipartFile> files = attachmentService.downloadAll(refs);
        result.put("imageCount", files.size());

        if (files.isEmpty()) {
            result.put("ok", true);
            result.put("message", "no image attachments");
            return ResponseEntity.ok(result);
        }

        try {
            AddReceiptsToPageResponse added = receiptPageService.addReceiptsToPage(pageId, files, null);
            result.put("ok", true);
            result.put("added", added);
        } catch (Exception e) {
            log.error("[CommentsProcess] addReceiptsToPage 실패. pageId={}, err={}", pageId, e.getMessage(), e);
            result.put("ok", false);
            result.put("stage", "addReceiptsToPage");
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
