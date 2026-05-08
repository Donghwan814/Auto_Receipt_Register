package com.example.receiptmemo.notion.controller;

import com.example.receiptmemo.global.config.NotionConfig;
import com.example.receiptmemo.notion.service.NotionService;
import com.example.receiptmemo.notion.service.NotionWebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Tag(name = "NotionWebhook", description = "Notion 웹훅")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notion")
public class NotionWebhookController {

    private final NotionWebhookService webhookService;
    private final NotionService notionService;
    private final NotionConfig notionConfig;

    @Operation(summary = "Notion 웹훅 수신")
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody(required = false) JsonNode payload) {
        NotionWebhookService.WebhookResult result = webhookService.handle(payload);
        if (result.isVerification) {
            Map<String, Object> body = new HashMap<>();
            body.put("verification_token", result.verificationToken);
            return ResponseEntity.ok(body);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("message", result.message);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "[Debug] Notion 페이지의 raw 댓글 JSON 조회")
    @GetMapping("/comments/debug")
    public ResponseEntity<?> debugComments(@RequestParam String pageId) {
        if (!notionConfig.isDebugComments()) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        return ResponseEntity.ok(notionService.listCommentsRaw(pageId));
    }
}
