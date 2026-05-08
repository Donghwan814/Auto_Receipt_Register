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
import java.util.UUID;

/**
 * Naver Clova OCR (General OCR) 기반 구현.
 *
 * 요청 형식 (base64)
 *   POST {invokeUrl}
 *   Header: X-OCR-SECRET: {secret}
 *   Body:
 *     {
 *       "version": "V2",
 *       "requestId": "...",
 *       "timestamp": ...,
 *       "lang": "ko",
 *       "images": [{ "format":"jpg", "name":"receipt", "data":"<base64>" }]
 *     }
 *
 * 응답: images[0].fields[*].inferText 를 줄바꿈을 추정해 합쳐 rawText 로 반환.
 *
 * 활성화 조건: ocr.engine=clova
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ocr.engine", havingValue = "clova")
public class ClovaOcrService implements OcrService {

    private static final double NEW_LINE_GAP_RATIO = 0.6; // 줄 높이 대비 y차이 임계값

    private final OcrProperties.Clova config;
    private final WebClient webClient;

    public ClovaOcrService(OcrProperties props, WebClient.Builder builder) {
        this.config = props.getClova();
        this.webClient = builder.build();

        if (config.getInvokeUrl() == null || config.getInvokeUrl().isBlank()
                || config.getSecret() == null || config.getSecret().isBlank()) {
            log.warn("[Clova] invoke-url 또는 secret 이 비어 있습니다. ocr.clova.* 또는 CLOVA_OCR_URL/CLOVA_OCR_SECRET 환경변수를 설정하세요.");
        }
    }

    @Override
    public String extractText(MultipartFile imageFile) {
        if (imageFile == null || imageFile.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 파일이 비어 있습니다.");
        }

        String format = OcrSupport.detectFormat(imageFile);
        String base64 = OcrSupport.toBase64(imageFile);

        Map<String, Object> body = Map.of(
                "version", "V2",
                "requestId", UUID.randomUUID().toString(),
                "timestamp", System.currentTimeMillis(),
                "lang", "ko",
                "images", List.of(Map.of(
                        "format", format,
                        "name", "receipt",
                        "data", base64
                ))
        );

        try {
            JsonNode resp = webClient.post()
                    .uri(config.getInvokeUrl())
                    .header("X-OCR-SECRET", config.getSecret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r ->
                            r.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(b -> {
                                        log.error("[Clova] error. status={}, body={}", r.statusCode(), b);
                                        return Mono.error(new CustomException(
                                                ErrorCode.OCR_FAILED,
                                                "Clova " + r.statusCode() + " : " + b));
                                    }))
                    .bodyToMono(JsonNode.class)
                    .block();

            String text = joinFields(resp);
            return OcrSupport.requireNonEmpty(text);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Clova] unexpected error", e);
            throw new CustomException(ErrorCode.OCR_FAILED, e.getMessage());
        }
    }

    /**
     * Clova 응답의 fields[] 를 순서대로 합치되, lineBreak=true 또는 y 좌표 변화가 크면 줄바꿈을 삽입한다.
     */
    String joinFields(JsonNode resp) {
        if (resp == null) return "";
        JsonNode fields = resp.path("images").path(0).path("fields");
        if (!fields.isArray() || fields.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        Double prevY = null;
        Double prevHeight = null;

        for (JsonNode f : fields) {
            String token = f.path("inferText").asText("");
            if (token.isEmpty()) continue;

            JsonNode vertices = f.path("boundingPoly").path("vertices");
            Double y = vertices.has(0) ? vertices.path(0).path("y").asDouble(Double.NaN) : null;
            Double bottom = vertices.has(2) ? vertices.path(2).path("y").asDouble(Double.NaN) : null;
            Double height = (y != null && bottom != null && !y.isNaN() && !bottom.isNaN())
                    ? Math.abs(bottom - y) : null;

            boolean lineBreak = f.path("lineBreak").asBoolean(false);
            boolean yJump = prevY != null && y != null && !y.isNaN()
                    && prevHeight != null
                    && Math.abs(y - prevY) > prevHeight * NEW_LINE_GAP_RATIO;

            if (sb.length() > 0) {
                sb.append(lineBreak || yJump ? "\n" : " ");
            }
            sb.append(token);

            if (lineBreak || yJump) {
                prevY = y;
                prevHeight = height;
            } else {
                if (prevY == null) prevY = y;
                if (prevHeight == null) prevHeight = height;
            }
        }
        return sb.toString().trim();
    }
}
