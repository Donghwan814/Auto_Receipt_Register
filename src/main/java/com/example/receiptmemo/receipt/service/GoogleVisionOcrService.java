package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.global.config.OcrProperties;
import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.PrematureCloseException;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Google Cloud Vision API (REST) 기반 OCR 구현.
 *
 * 안정화 정책:
 *  - connect 10s / response 90s / read 90s / write 30s timeout
 *  - 일시적 네트워크/5xx 오류는 retry (1s, 3s, 7s; 최대 3회)
 *  - 마지막까지 실패하면 OCR_FAILED CustomException 으로 명확히 던진다.
 *
 * 활성화 조건: ocr.engine=google
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ocr.engine", havingValue = "google")
public class GoogleVisionOcrService implements OcrService {

    private final OcrProperties.Google config;
    private final WebClient webClient;

    /** 재시도 backoff. 테스트에서 0으로 덮어쓸 수 있도록 final 아닌 필드. */
    long[] retryBackoffMs = {1000L, 3000L, 7000L};

    public GoogleVisionOcrService(OcrProperties props, WebClient.Builder builder) {
        this(props, defaultWebClient(builder));
    }

    /** 테스트 용도: WebClient 를 직접 주입. */
    GoogleVisionOcrService(OcrProperties props, WebClient webClient) {
        this.config = props.getGoogle();
        this.webClient = webClient;

        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("[GoogleVision] api-key 가 비어 있습니다. ocr.google.api-key 또는 GOOGLE_VISION_API_KEY 를 설정하세요.");
        }
    }

    private static WebClient defaultWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(90))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(90, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        return builder
                .clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    @Override
    public String extractText(MultipartFile imageFile) {
        if (imageFile == null || imageFile.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 파일이 비어 있습니다.");
        }
        String filename = imageFile.getOriginalFilename();
        long size = imageFile.getSize();
        log.info("[OCR] Google Vision request start. filename={}, size={}", filename, size);

        String base64 = OcrSupport.toBase64(imageFile);
        Map<String, Object> body = Map.of(
                "requests", List.of(Map.of(
                        "image", Map.of("content", base64),
                        "features", List.of(Map.of("type", config.getFeatureType())),
                        "imageContext", Map.of("languageHints", config.getLanguageHints())
                ))
        );
        String url = config.getEndpoint() + "?key=" + config.getApiKey();

        try {
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
                    .retryWhen(buildRetrySpec(filename))
                    .block();

            String text = resp == null ? null
                    : resp.path("responses").path(0).path("fullTextAnnotation").path("text").asText(null);
            return OcrSupport.requireNonEmpty(text);
        } catch (CustomException e) {
            log.error("[OCR] failed after retries. filename={}, reason={}", filename, e.getMessage());
            throw e;
        } catch (Exception e) {
            // retryWhen 이 모두 실패하면 onRetryExhaustedThrow 가 던진 RuntimeException 이 여기로 옴
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[OCR] failed after retries. filename={}, reason={}", filename, cause.getMessage());
            throw new CustomException(ErrorCode.OCR_FAILED,
                    "Google Vision 호출 실패: " + cause.getMessage());
        }
    }

    private Retry buildRetrySpec(String filename) {
        long[] delays = retryBackoffMs;
        return Retry.from(signals -> signals
                .flatMap(rs -> {
                    long attempt = rs.totalRetries(); // 0,1,2,...
                    Throwable t = rs.failure();
                    if (!isRetryable(t)) {
                        return Mono.error(t);
                    }
                    if (attempt >= delays.length) {
                        return Mono.error(t);
                    }
                    long delayMs = delays[(int) attempt];
                    log.warn("[OCR] retry attempt={}, filename={}, reason={}",
                            attempt + 1, filename, t.getClass().getSimpleName() + ": " + t.getMessage());
                    return Mono.delay(Duration.ofMillis(delayMs));
                }));
    }

    /** 일시적 네트워크/5xx 만 재시도. 4xx 등 명시적 클라이언트 오류는 재시도 X. */
    static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        if (t instanceof PrematureCloseException) return true;
        if (t instanceof WebClientRequestException) return true;
        if (t instanceof TimeoutException) return true;
        if (t instanceof IOException) return true;
        if (t instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();
        }
        if (t instanceof CustomException ce && ce.getErrorCode() == ErrorCode.OCR_FAILED) {
            // onStatus 에서 5xx 라면 메시지에 5xx 가 들어 있다 — 보수적으로 5xx 만 재시도.
            String msg = ce.getMessage();
            if (msg != null && (msg.contains("5") && (msg.contains("500") || msg.contains("502")
                    || msg.contains("503") || msg.contains("504")))) {
                return true;
            }
            return false;
        }
        // cause chain 도 확인
        Throwable cause = t.getCause();
        if (cause != null && cause != t) return isRetryable(cause);
        return false;
    }
}
