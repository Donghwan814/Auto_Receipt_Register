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

    /**
     * 재시도 딜레이(ms). 테스트에서 0으로 덮어쓸 수 있도록 final 아닌 필드.
     * Android Notion 등 늦게 attachments/blocks 가 반영되는 케이스에 대비하여 최대 ~40s.
     */
    long[] retryDelaysMs = {0L, 2000L, 5000L, 10000L, 20000L, 40000L};

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

        // 이미지 수집: 댓글 attachments + 페이지 block 본문 이미지를 병합해서 retry.
        // Android Notion 등에서 댓글 attachments 가 늦게 반영되거나, 본문 image block 으로
        // 들어오는 케이스를 모두 잡기 위함.
        List<ReceiptAttachmentService.ImageRef> refs = fetchAttachmentsWithRetry(pageId, commentId);
        if (refs == null || refs.isEmpty()) {
            log.info("[Webhook] 이미지 첨부 없음. pageId={}", pageId);
            recordEvent(dedupKey, eventType, pageId, commentId);
            return WebhookResult.ok("no attachments");
        }
        List<MultipartFile> files = attachmentService.downloadAll(refs);
        if (files == null || files.isEmpty()) {
            log.info("[Webhook] 다운로드된 이미지 없음. pageId={}, refs={}", pageId, refs.size());
            recordEvent(dedupKey, eventType, pageId, commentId);
            return WebhookResult.ok("no downloads");
        }

        try {
            // resync: 현재 페이지에 존재하는 이미지를 source of truth 로 보고 재집계.
            // 사용자가 영수증을 삭제/교체한 경우에도 안정적으로 동기화된다.
            receiptPageService.resyncReceiptsForPage(pageId, files, null);
            log.info("[Webhook] 영수증 resync 완료. pageId={}, count={}", pageId, files.size());
        } catch (Exception e) {
            log.error("[Webhook] resyncReceiptsForPage 실패. pageId={}, err={}", pageId, e.getMessage(), e);
        }

        recordEvent(dedupKey, eventType, pageId, commentId);
        return WebhookResult.ok("processed");
    }

    /**
     * 댓글 attachments + page blocks 를 모두 조회해 이미지 후보를 수집한다.
     * retry 는 retryDelaysMs (즉시 / 2s / 5s / 10s / 20s / 40s) 로 최대 ~40s.
     * 어떤 retry 에서든 최소 1개 이상 이미지가 잡히면 즉시 반환.
     */
    List<ReceiptAttachmentService.ImageRef> fetchAttachmentsWithRetry(String pageId, String commentId) {
        long[] delays = retryDelaysMs;
        List<ReceiptAttachmentService.ImageRef> last = List.of();
        for (int attempt = 0; attempt < delays.length; attempt++) {
            if (delays[attempt] > 0) {
                try { Thread.sleep(delays[attempt]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }

            List<ReceiptAttachmentService.ImageRef> commentRefs = List.of();
            List<ReceiptAttachmentService.ImageRef> blockRefs = List.of();
            try {
                String commentsRaw = notionService.listCommentsRaw(pageId);
                commentRefs = attachmentService.extractImageAttachmentsFromRaw(commentsRaw, commentId);
            } catch (Exception e) {
                log.warn("[Webhook] 댓글 조회 실패. attempt={}, pageId={}, err={}", attempt, pageId, e.getMessage());
            }
            try {
                String blocksRaw = notionService.listBlockChildrenRaw(pageId);
                blockRefs = attachmentService.extractImageRefsFromBlocksRaw(blocksRaw);
            } catch (Exception e) {
                log.warn("[Webhook] block children 조회 실패. attempt={}, pageId={}, err={}", attempt, pageId, e.getMessage());
            }

            List<ReceiptAttachmentService.ImageRef> merged = mergeByUrl(commentRefs, blockRefs);
            log.info("[Webhook] retry attempt={}, comments={}, blocks={}, merged={}, fallbackBlocks={}",
                    attempt, commentRefs.size(), blockRefs.size(), merged.size(),
                    (commentRefs.isEmpty() && !blockRefs.isEmpty()));

            if (!merged.isEmpty()) {
                if (attempt > 0) log.info("[Webhook] retry 성공 (attempt={}). count={}", attempt, merged.size());
                return merged;
            }
            last = merged;
        }
        return last;
    }

    /** url 기준 중복 제거. a 가 우선. null 안전. */
    private List<ReceiptAttachmentService.ImageRef> mergeByUrl(List<ReceiptAttachmentService.ImageRef> a,
                                                               List<ReceiptAttachmentService.ImageRef> b) {
        java.util.LinkedHashMap<String, ReceiptAttachmentService.ImageRef> map = new java.util.LinkedHashMap<>();
        if (a != null) for (ReceiptAttachmentService.ImageRef r : a) {
            if (r != null && r.getUrl() != null) map.putIfAbsent(r.getUrl(), r);
        }
        if (b != null) for (ReceiptAttachmentService.ImageRef r : b) {
            if (r != null && r.getUrl() != null) map.putIfAbsent(r.getUrl(), r);
        }
        return new java.util.ArrayList<>(map.values());
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
