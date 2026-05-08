package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.ledger.service.ReceiptPageService;
import com.example.receiptmemo.notion.persistence.WebhookEventLog;
import com.example.receiptmemo.notion.persistence.WebhookEventLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Notion 웹훅 처리.
 *  - verification_token : echo
 *  - comment.created / comment.updated : pageId+commentId 추출 후 첨부 처리
 *  - page.content_updated : pageId 가 있으면 댓글 첨부 재시도 (중복은 fileHash 로 차단)
 *  - file_upload.completed : pageId 가 있으면 동일 처리
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

        if (payload.has("verification_token")) {
            String token = payload.get("verification_token").asText();
            log.info("[Webhook] verification_token 수신. token={}", token);
            return WebhookResult.verification(token);
        }

        String eventType = textOrNull(payload, "type");
        String eventId = resolveEventId(payload);

        String pageId = extractPageId(payload);
        String commentId = extractCommentId(payload);

        if (pageId == null || pageId.isBlank()) {
            log.warn("[Webhook] pageId 누락. type={}, payload entity={}", eventType, payload.path("entity"));
            return WebhookResult.ok("no pageId");
        }

        String dedupKey = eventId != null ? eventId : fallbackKey(pageId, commentId, payload);
        if (dedupKey != null && eventLogRepository.existsByEventId(dedupKey)) {
            log.info("[Webhook] 중복 이벤트, 스킵. eventId={}", dedupKey);
            return WebhookResult.ok("duplicate");
        }

        log.info("[Webhook] 처리 시작. type={}, eventId={}, pageId={}, commentId={}",
                eventType, dedupKey, pageId, commentId);

        // 댓글 raw JSON 조회
        String rawJson;
        try {
            rawJson = notionService.listCommentsRaw(pageId);
        } catch (Exception e) {
            log.error("[Webhook] 댓글 조회 실패. pageId={}, err={}", pageId, e.getMessage());
            return WebhookResult.ok("comment fetch failed");
        }

        // 이미지 첨부 추출 (commentId 가 있으면 해당 댓글 우선, 없으면 fallback)
        List<ReceiptAttachmentService.ImageRef> refs =
                attachmentService.extractImageAttachmentsFromRaw(rawJson, commentId);

        List<MultipartFile> files = attachmentService.downloadAll(refs);
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
