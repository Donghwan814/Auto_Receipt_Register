package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.ledger.service.ReceiptPageService;
import com.example.receiptmemo.notion.dto.api.NotionCommentResponse;
import com.example.receiptmemo.notion.persistence.WebhookEventLog;
import com.example.receiptmemo.notion.persistence.WebhookEventLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Notion 웹훅 처리.
 *  - verification_token : echo
 *  - comment.created / comment.updated : pageId+commentId 추출 후 첨부 처리
 *  - page.content_updated : log only
 *  - 중복 eventId 는 skip
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotionWebhookService {

    private final NotionService notionService;
    private final ReceiptAttachmentService attachmentService;
    private final ReceiptPageService receiptPageService;
    private final WebhookEventLogRepository eventLogRepository;

    @Transactional
    public WebhookResult handle(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isEmpty()) {
            return WebhookResult.ok("empty payload");
        }

        // 1) verification token (Notion 웹훅 검증)
        if (payload.has("verification_token")) {
            String token = payload.get("verification_token").asText();
            log.info("[Webhook] verification_token 수신. token={}", token);
            return WebhookResult.verification(token);
        }

        String eventType = textOrNull(payload, "type");
        String eventId = resolveEventId(payload);

        // page.content_updated 등은 단순 로그
        if (eventType != null && eventType.startsWith("page.")) {
            log.info("[Webhook] page event 수신 (스텁). type={}, eventId={}", eventType, eventId);
            recordEvent(eventId, eventType, null, null);
            return WebhookResult.ok("page event logged");
        }

        // pageId / commentId 추출
        String pageId = extractPageId(payload);
        String commentId = extractCommentId(payload);

        if (pageId == null || pageId.isBlank()) {
            log.warn("[Webhook] pageId 누락. type={}, payload entity={}", eventType, payload.path("entity"));
            return WebhookResult.ok("no pageId");
        }

        // dedup
        String dedupKey = eventId != null ? eventId : fallbackKey(pageId, commentId, payload);
        if (dedupKey != null && eventLogRepository.existsByEventId(dedupKey)) {
            log.info("[Webhook] 중복 이벤트, 스킵. eventId={}", dedupKey);
            return WebhookResult.ok("duplicate");
        }

        log.info("[Webhook] 처리 시작. type={}, eventId={}, pageId={}, commentId={}",
                eventType, dedupKey, pageId, commentId);

        // 댓글 목록 조회 -> 이미지 첨부 다운로드
        List<NotionCommentResponse> comments;
        try {
            comments = notionService.listComments(pageId);
        } catch (Exception e) {
            log.error("[Webhook] 댓글 조회 실패. pageId={}, err={}", pageId, e.getMessage());
            return WebhookResult.ok("comment fetch failed");
        }

        List<NotionCommentResponse> targetComments;
        if (commentId != null && !commentId.isBlank()) {
            targetComments = new ArrayList<>();
            for (NotionCommentResponse c : comments) {
                if (commentId.equals(c.getId())) targetComments.add(c);
            }
            if (targetComments.isEmpty()) {
                log.warn("[Webhook] commentId={} 가 댓글 목록에 없음 (전체 처리).", commentId);
                targetComments = comments;
            }
        } else {
            targetComments = comments;
        }

        List<MultipartFile> files = attachmentService.downloadAllImages(targetComments);
        if (files.isEmpty()) {
            log.info("[Webhook] 이미지 첨부 없음. pageId={}", pageId);
            recordEvent(dedupKey, eventType, pageId, commentId);
            return WebhookResult.ok("no attachments");
        }

        try {
            receiptPageService.addReceiptsToPage(pageId, files, null);
            log.info("[Webhook] 영수증 처리 완료. pageId={}, count={}", pageId, files.size());
        } catch (Exception e) {
            log.error("[Webhook] addReceiptsToPage 실패. pageId={}, err={}", pageId, e.getMessage(), e);
        }

        recordEvent(dedupKey, eventType, pageId, commentId);
        return WebhookResult.ok("processed");
    }

    private void recordEvent(String eventId, String eventType, String pageId, String commentId) {
        if (eventId == null || eventId.isBlank()) return;
        if (eventLogRepository.existsByEventId(eventId)) return;
        try {
            eventLogRepository.save(WebhookEventLog.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .pageId(pageId)
                    .commentId(commentId)
                    .build());
        } catch (Exception e) {
            log.warn("[Webhook] eventLog 저장 실패. eventId={}, err={}", eventId, e.getMessage());
        }
    }

    private String resolveEventId(JsonNode p) {
        for (String f : new String[]{"id", "event_id", "delivery_id"}) {
            String v = textOrNull(p, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String fallbackKey(String pageId, String commentId, JsonNode payload) {
        String t = textOrNull(payload, "created_time");
        return pageId + "|" + (commentId == null ? "" : commentId) + "|" + (t == null ? "" : t);
    }

    private String extractPageId(JsonNode p) {
        // entity.id (page) 또는 data.parent.id 또는 data.page_id
        JsonNode entity = p.path("entity");
        if (entity.isObject()) {
            String type = entity.path("type").asText("");
            String id = entity.path("id").asText(null);
            if ("page".equals(type) && id != null && !id.isBlank()) return id;
        }
        JsonNode data = p.path("data");
        if (data.isObject()) {
            String pid = data.path("page_id").asText(null);
            if (pid != null && !pid.isBlank()) return pid;
            JsonNode parent = data.path("parent");
            if (parent.isObject()) {
                String ptype = parent.path("type").asText("");
                String pid2 = parent.path("page_id").asText(null);
                if (pid2 != null && !pid2.isBlank()) return pid2;
                if ("page_id".equals(ptype)) {
                    String alt = parent.path("id").asText(null);
                    if (alt != null && !alt.isBlank()) return alt;
                }
            }
        }
        // top-level page_id
        String top = textOrNull(p, "page_id");
        if (top != null) return top;
        return null;
    }

    private String extractCommentId(JsonNode p) {
        JsonNode entity = p.path("entity");
        if (entity.isObject()) {
            String type = entity.path("type").asText("");
            String id = entity.path("id").asText(null);
            if ("comment".equals(type) && id != null && !id.isBlank()) return id;
        }
        JsonNode data = p.path("data");
        if (data.isObject()) {
            String cid = data.path("comment_id").asText(null);
            if (cid != null && !cid.isBlank()) return cid;
        }
        return textOrNull(p, "comment_id");
    }

    private String textOrNull(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText(null);
        return (t == null || t.isBlank()) ? null : t;
    }

    public static class WebhookResult {
        public final boolean isVerification;
        public final String verificationToken;
        public final String message;

        private WebhookResult(boolean isVerif, String token, String msg) {
            this.isVerification = isVerif;
            this.verificationToken = token;
            this.message = msg;
        }
        public static WebhookResult ok(String m) { return new WebhookResult(false, null, m); }
        public static WebhookResult verification(String t) { return new WebhookResult(true, t, "verification"); }
    }
}
