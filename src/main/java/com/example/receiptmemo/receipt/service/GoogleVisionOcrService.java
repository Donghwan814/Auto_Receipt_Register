package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.global.config.OcrProperties;
import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Google Cloud Vision API (REST) 기반 OCR 구현.
 *
 * 동작 방식
 *  1. 이미지 → base64
 *  2. POST {endpoint}?key={apiKey} 로 images:annotate 요청
 *  3. 응답 JSON 의 fullTextAnnotation.text 를 그대로 rawText 로 사용
 *
 * 활성화 조건: ocr.engine=google
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ocr.engine", havingValue = "google")
public class GoogleVisionOcrService implements OcrService {

    private final OcrProperties.Google config;
    private final WebClient webClient;

    public GoogleVisionOcrService(OcrProperties props, WebClient.Builder builder) {
        this.config = props.getGoogle();
        this.webClient = builder.build();

        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("[GoogleVision] api-key 가 비어 있습니다. ocr.google.api-key 또는 GOOGLE_VISION_API_KEY 를 설정하세요.");
        }
    }

    @Override
    public String extractText(MultipartFile imageFile) {
        if (imageFile == null || imageFile.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 파일이 비어 있습니다.");
        }
        String base64 = OcrSupport.toBase64(imageFile);

        Map<String, Object> body = Map.of(
                "requests", List.of(Map.of(
                        "image", Map.of("content", base64),
                        "features", List.of(Map.of("type", config.getFeatureType())),
                        "imageContext", Map.of("languageHints", config.getLanguageHints())
                ))
        );

        try {
            String url = config.getEndpoint() + "?key=" + config.getApiKey();
            JsonNode resp = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r ->
                            r.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(b -> {
                                        log.error("[GoogleVision] error. status={}, body={}", r.statusCode(), b);
                                        return Mono.error(new CustomException(
                                                ErrorCode.OCR_FAILED,
                                                "GoogleVision " + r.statusCode() + " : " + b));
                                    }))
                    .bodyToMono(JsonNode.class)
                    .block();

            String text = resp == null ? null
                    : resp.path("responses").path(0).path("fullTextAnnotation").path("text").asText(null);
            return OcrSupport.requireNonEmpty(text);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GoogleVision] unexpected error", e);
            throw new CustomException(ErrorCode.OCR_FAILED, e.getMessage());
        }
    }
}
