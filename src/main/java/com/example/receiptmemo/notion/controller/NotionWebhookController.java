package com.example.receiptmemo.notion.controller;

import com.example.receiptmemo.notion.service.NotionWebhookAsyncProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Notion 웹훅 수신.
 *  - verification_token: 즉시 echo
 *  - 그 외 이벤트: 즉시 200 OK 반환 후 비동기로 처리.
 *  - 어떤 경우에도 5xx/4xx 를 반환하지 않는다 (Notion delivery pause 방지).
 */
@Slf4j
@Tag(name = "NotionWebhook", description = "Notion 웹훅")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notion")
public class NotionWebhookController {

    private final NotionWebhookAsyncProcessor asyncProcessor;

    @Operation(summary = "Notion 웹훅 수신")
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody(required = false) JsonNode payload) {
        try {
            if (payload != null && payload.has("verification_token")) {
                String token = payload.get("verification_token").asText();
                log.info("[Webhook] verification_token 수신.");
                Map<String, Object> body = new HashMap<>();
                body.put("verification_token", token);
                return ResponseEntity.ok(body);
            }

            String type = payload == null ? null : payload.path("type").asText(null);
            log.info("[Webhook] 수신 → 비동기 처리 큐 적재. type={}", type);

            try {
                asyncProcessor.process(payload);
            } catch (Throwable t) {
                // executor saturation 등으로 submit 자체가 실패해도 200 OK 유지
                log.error("[Webhook] 비동기 큐 적재 실패: {}", t.getMessage(), t);
            }
        } catch (Throwable t) {
            // payload 파싱 실패 등 어떤 예외에도 200 을 반환한다.
            log.error("[Webhook] payload 처리 중 예외: {}", t.getMessage(), t);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("message", "accepted");
        return ResponseEntity.ok(body);
    }
}
