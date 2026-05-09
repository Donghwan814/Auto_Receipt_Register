package com.example.receiptmemo;

import com.example.receiptmemo.receipt.service.GoogleVisionOcrService;
import com.example.receiptmemo.receipt.service.OcrService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cloud Run 부팅 단계에서 발생했던 "No default constructor for GoogleVisionOcrService"
 * 같은 Bean 생성 실패를 회귀 방지하기 위한 컨텍스트 부팅 테스트.
 *
 * ocr.engine=google 로 띄워 실제 production 프로필과 동일한 빈 그래프가 만들어지는지 확인한다.
 */
@SpringBootTest(properties = {
        "ocr.engine=google",
        "ocr.google.api-key=test-key",
        "notion.api-key=test",
        "notion.database-id=test-db",
        "spring.datasource.url=jdbc:h2:mem:ctxtest;MODE=LEGACY;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ReceiptMemoApplicationContextTest {

    @Autowired OcrService ocrService;

    @Test
    void contextLoads() {
        // Spring context 가 떠야 통과. 추가로 ocr.engine=google 일 때 GoogleVisionOcrService 가 빈으로 잡혔는지 확인.
        assertThat(ocrService).isInstanceOf(GoogleVisionOcrService.class);
    }
}
