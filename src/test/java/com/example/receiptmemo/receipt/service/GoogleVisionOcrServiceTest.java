package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.global.config.OcrProperties;
import com.example.receiptmemo.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleVisionOcrServiceTest {

    private static final String VISION_OK_BODY =
            "{\"responses\":[{\"fullTextAnnotation\":{\"text\":\"마시타야\\n합계 25000\"}}]}";

    private OcrProperties props() {
        OcrProperties p = new OcrProperties();
        p.getGoogle().setApiKey("test-key");
        p.getGoogle().setEndpoint("https://vision.googleapis.com/v1/images:annotate");
        return p;
    }

    private MockMultipartFile sampleImage() {
        return new MockMultipartFile("files", "receipt.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private GoogleVisionOcrService serviceWith(ExchangeFunction ef) {
        WebClient client = WebClient.builder().exchangeFunction(ef).build();
        GoogleVisionOcrService svc = new GoogleVisionOcrService(props(), client);
        svc.retryBackoffMs = new long[]{0L, 0L, 0L}; // 테스트 빠르게
        return svc;
    }

    @Test
    void isRetryable_네트워크_예외는_재시도_대상() {
        assertThat(GoogleVisionOcrService.isRetryable(new IOException("Connection prematurely closed BEFORE response"))).isTrue();
        assertThat(GoogleVisionOcrService.isRetryable(new TimeoutException())).isTrue();
        assertThat(GoogleVisionOcrService.isRetryable(new IOException("reset"))).isTrue();
        assertThat(GoogleVisionOcrService.isRetryable(
                new WebClientRequestException(new RuntimeException("boom"), null,
                        URI.create("https://x"), org.springframework.http.HttpHeaders.EMPTY))).isTrue();
        assertThat(GoogleVisionOcrService.isRetryable(new IllegalArgumentException("4xx"))).isFalse();
    }

    @Test
    void PrematureCloseException_발생시_재시도_후_성공() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction ef = req -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                return Mono.error(new IOException("Connection prematurely closed BEFORE response"));
            }
            return Mono.just(jsonResponse(HttpStatus.OK, VISION_OK_BODY));
        };

        String text = serviceWith(ef).extractText(sampleImage());

        assertThat(text).contains("마시타야");
        assertThat(calls.get()).isEqualTo(3); // 2 retries
    }

    @Test
    void 모든_재시도가_실패하면_OCR_FAILED_던진다() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction ef = req -> {
            calls.incrementAndGet();
            return Mono.error(new IOException("Connection prematurely closed BEFORE response"));
        };

        assertThatThrownBy(() -> serviceWith(ef).extractText(sampleImage()))
                .isInstanceOf(CustomException.class);
        assertThat(calls.get()).isEqualTo(4); // 1 + 3 retries
    }

    @Test
    void _4xx_응답은_재시도하지_않고_즉시_실패() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction ef = req -> {
            calls.incrementAndGet();
            return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, "{\"error\":\"bad\"}"));
        };

        assertThatThrownBy(() -> serviceWith(ef).extractText(sampleImage()))
                .isInstanceOf(CustomException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
