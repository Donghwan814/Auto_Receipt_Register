package com.example.receiptmemo.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Notion 웹훅 비동기 처리.
 * Controller 는 payload 를 받자마자 200 OK 를 반환하고,
 * 실제 OCR/다운로드/Notion update 는 이 컴포넌트가 백그라운드에서 수행한다.
 *
 * 이 안에서 어떤 예외가 나도 로깅만 하고 삼킨다 (이미 200 OK 가 나간 뒤).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotionWebhookAsyncProcessor {

    private final NotionWebhookService webhookService;

    @Async("notionWebhookExecutor")
    public void process(JsonNode payload) {
        try {
            NotionWebhookService.WebhookResult r = webhookService.handle(payload);
            log.info("[WebhookAsync] 처리 완료. message={}", r.message);
        } catch (Throwable t) {
            log.error("[WebhookAsync] 비동기 처리 실패: {}", t.getMessage(), t);
        }
    }
}
