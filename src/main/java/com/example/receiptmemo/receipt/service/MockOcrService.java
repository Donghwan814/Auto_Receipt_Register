package com.example.receiptmemo.receipt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 개발 단계에서 OCR 결과를 흉내내는 Mock 구현체.
 * application.yaml 의 ocr.engine=mock 일 때(또는 미지정일 때) 활성화된다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ocr.engine", havingValue = "mock", matchIfMissing = true)
public class MockOcrService implements OcrService {

    @Override
    public String extractText(MultipartFile image) {
        if (image != null) {
            log.info("[MockOCR] received image name={}, size={}",
                    image.getOriginalFilename(), image.getSize());
        }
        // 실제 구현 전까지 사용할 임시 결과
        return """
                블랙라멘 1
                시오라멘 1
                공기밥 1
                합계 25,000원
                """;
    }
}
