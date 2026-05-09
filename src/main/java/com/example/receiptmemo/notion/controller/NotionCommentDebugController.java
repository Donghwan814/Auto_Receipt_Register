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
 * Notion 댓글/블록 디버그 + 수동 처리 엔드포인트.
 * 디버그 엔드포인트는 NOTION_WEBHOOK_DEBUG_COMMENTS=true 일 때만 활성화.
 */
@Slf4j
@Tag(name = "NotionCommentDebug", description = "Notion 댓글/블록 디버그 및 수동 처리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notion")
public class NotionCommentDebugController {

    private final NotionService notionService;
    private final NotionConfig notionConfig;
    private final ReceiptAttachmentService attachmentService;
    private final ReceiptPageService receiptPageService;

    @Operation(summary = "[Debug] Notion 페이지의 raw 댓글 JSON 조회")
    @GetMapping(value = "/comments/debug", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> debugComments(@RequestParam String pageId) {
        ResponseEntity<?> denied = denyIfDebugDisabled();
        if (denied != null) return denied;
        String raw = notionService.listCommentsRaw(pageId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(raw);
    }

    @Operation(summary = "[Debug] Notion 페이지의 raw block children JSON 조회")
    @GetMapping(value = "/blocks/debug", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> debugBlocks(@RequestParam String pageId) {
        ResponseEntity<?> denied = denyIfDebugDisabled();
        if (denied != null) return denied;
        String raw = notionService.listBlockChildrenRaw(pageId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(raw);
    }

    @Operation(summary = "[Debug] 페이지의 댓글/블록 이미지 후보 통합 조회")
    @GetMapping(value = "/images/debug", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> debugImages(@RequestParam String pageId) {
        ResponseEntity<?> denied = denyIfDebugDisabled();
        if (denied != null) return denied;

        String commentsRaw = safeCall(() -> notionService.listCommentsRaw(pageId));
        String blocksRaw = safeCall(() -> notionService.listBlockChildrenRaw(pageId));
        List<ReceiptAttachmentService.ImageRef> commentRefs =
                attachmentService.extractImageAttachmentsFromRaw(commentsRaw);
        List<ReceiptAttachmentService.ImageRef> blockRefs =
                attachmentService.extractImageRefsFromBlocksRaw(blocksRaw);
        List<ReceiptAttachmentService.ImageRef> merged =
                attachmentService.dedupeByUrl(commentRefs, blockRefs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageId", pageId);
        body.put("commentImageRefs", commentRefs);
        body.put("blockImageRefs", blockRefs);
        body.put("mergedImageRefs", merged);
        body.put("finalCount", merged.size());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Notion 페이지의 댓글/블록 이미지를 수동으로 처리. mode=resync(기본) | append")
    @PostMapping("/comments/process")
    public ResponseEntity<?> process(@RequestParam String pageId,
                                     @RequestParam(value = "mode", required = false, defaultValue = "resync") String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", pageId);
        result.put("mode", mode);

        List<ReceiptAttachmentService.ImageRef> commentRefs = List.of();
        List<ReceiptAttachmentService.ImageRef> blockRefs = List.of();
        try {
            String commentsRaw = notionService.listCommentsRaw(pageId);
            commentRefs = attachmentService.extractImageAttachmentsFromRaw(commentsRaw);
        } catch (Exception e) {
            log.warn("[CommentsProcess] 댓글 조회 실패. pageId={}, err={}", pageId, e.getMessage());
        }
        try {
            String blocksRaw = notionService.listBlockChildrenRaw(pageId);
            blockRefs = attachmentService.extractImageRefsFromBlocksRaw(blocksRaw);
        } catch (Exception e) {
            log.warn("[CommentsProcess] block 조회 실패. pageId={}, err={}", pageId, e.getMessage());
        }

        List<ReceiptAttachmentService.ImageRef> merged = attachmentService.dedupeByUrl(commentRefs, blockRefs);
        result.put("commentImageCount", commentRefs.size());
        result.put("blockImageCount", blockRefs.size());
        result.put("imageRefCount", merged.size());

        List<MultipartFile> files = attachmentService.downloadAll(merged);
        result.put("imageCount", files.size());

        boolean useAppend = "append".equalsIgnoreCase(mode);
        if (files.isEmpty()) {
            result.put("ok", true);
            result.put("message", "no image attachments");
            return ResponseEntity.ok(result);
        }

        try {
            AddReceiptsToPageResponse processed = useAppend
                    ? receiptPageService.addReceiptsToPage(pageId, files, null)
                    : receiptPageService.resyncReceiptsForPage(pageId, files, null);
            result.put("ok", processed.isSuccess());
            result.put("processed", processed);
        } catch (Exception e) {
            log.error("[CommentsProcess] {} 실패. pageId={}, err={}",
                    useAppend ? "addReceiptsToPage" : "resyncReceiptsForPage", pageId, e.getMessage(), e);
            result.put("ok", false);
            result.put("stage", useAppend ? "addReceiptsToPage" : "resyncReceiptsForPage");
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> denyIfDebugDisabled() {
        if (!notionConfig.isDebugComments()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "forbidden",
                    "message", "debug endpoint disabled. set NOTION_WEBHOOK_DEBUG_COMMENTS=true to enable."
            ));
        }
        return null;
    }

    private interface Supplier<T> { T get(); }
    private <T> T safeCall(Supplier<T> s) {
        try { return s.get(); } catch (Exception e) { return null; }
    }
}
