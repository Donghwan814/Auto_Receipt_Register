package com.example.receiptmemo.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * application.yaml 의 ocr.* 프로퍼티.
 *
 *  ocr.engine             : mock | google | clova  (기본 mock)
 *  ocr.google.api-key     : Google Cloud Vision API key
 *  ocr.clova.secret       : Naver Clova OCR X-OCR-SECRET
 *  ocr.clova.invoke-url   : Clova OCR Invoke URL
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private String engine = "mock";
    private Google google = new Google();
    private Clova clova = new Clova();

    @Getter @Setter
    public static class Google {
        private String apiKey;
        private String endpoint = "https://vision.googleapis.com/v1/images:annotate";
        private String featureType = "DOCUMENT_TEXT_DETECTION";
        private List<String> languageHints = List.of("ko", "en");
    }

    @Getter @Setter
    public static class Clova {
        private String secret;
        private String invokeUrl;
        private List<String> templateIds = List.of();
    }
}
