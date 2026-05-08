package com.example.receiptmemo.ledger;

import com.example.receiptmemo.ledger.dto.AddReceiptsToPageResponse;
import com.example.receiptmemo.ledger.dto.CreateReceiptPageResponse;
import com.example.receiptmemo.ledger.persistence.ProcessedReceiptRepository;
import com.example.receiptmemo.ledger.service.ReceiptPageService;
import com.example.receiptmemo.notion.service.NotionService;
import com.example.receiptmemo.receipt.service.OcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "ocr.engine=mock",
        "notion.api-key=test",
        "notion.database-id=test-db",
        // 테스트 격리용 in-memory H2
        "spring.datasource.url=jdbc:h2:mem:rptest;MODE=LEGACY;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class ReceiptPageServiceTest {

    @Autowired ReceiptPageService service;
    @Autowired ProcessedReceiptRepository repository;
    @MockBean NotionService notionService;
    @MockBean OcrService ocrService;

    private static final String MASIRAMEN_1 = """
            마시타야
            2026년 5월 3일
            블랙라멘 1
            시오라멘 1
            공기밥 1
            합계 25,000원
            """;
    private static final String MASIRAMEN_2 = """
            마시타야
            2026년 5월 3일
            제로콜라 1
            교자 1
            합계 8,500원
            """;
    private static final String COFFEEBEAN = """
            커피빈 홍대점
            2026년 5월 3일
            아이스 아메리카노 2
            치즈케이크 1
            합계 18,900원
            """;

    @BeforeEach
    void setup() {
        // 각 테스트가 자기 데이터만 보도록
        repository.deleteAll();
        reset(notionService, ocrService);
    }

    private MultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/jpeg", bytes);
    }

    @Test
    void 마시타야_영수증_1개_create_page() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);
        when(notionService.createExpensePage(anyString(), anyInt(), any(), anyString(), anyString())).thenReturn("new-page-1");

        CreateReceiptPageResponse resp = service.createPage(List.of(file("a.jpg", "a".getBytes())), null);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getPageId()).isEqualTo("new-page-1");
        assertThat(resp.getTitle()).isEqualTo("🍜 마시타야");
        assertThat(resp.getTotalAmount()).isEqualTo(25000);
        assertThat(resp.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥");
        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 5, 3));

        verify(notionService).createExpensePage(eq("🍜 마시타야"), eq(25000), eq(LocalDate.of(2026,5,3)),
                eq("식비"), eq("블랙라멘 + 시오라멘 + 공기밥"));
        verify(notionService).updateExpensePage(eq("new-page-1"), anyString(), anyInt(), any(), anyString(), anyString());
    }

    @Test
    void 같은_pageId_에_영수증_2개_금액_메모_합산() {
        // 두 OCR 결과를 순서대로 반환
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1, MASIRAMEN_2);

        AddReceiptsToPageResponse resp = service.addReceiptsToPage(
                "page-MM",
                List.of(file("a.jpg", "a".getBytes()), file("b.jpg", "b".getBytes())),
                null);

        assertThat(resp.getTotalAmount()).isEqualTo(33500);
        assertThat(resp.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥 + 제로콜라 + 교자");
        assertThat(resp.getTitle()).isEqualTo("🍜 마시타야");
        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(resp.getReceipts()).hasSize(2);
        assertThat(resp.getReceipts()).allMatch(r -> !r.isDuplicated());

        verify(notionService).updateExpensePage(eq("page-MM"), eq("🍜 마시타야"), eq(33500),
                eq(LocalDate.of(2026,5,3)), eq("식비"), eq("블랙라멘 + 시오라멘 + 공기밥 + 제로콜라 + 교자"));
    }

    @Test
    void 같은_파일_해시_중복은_합산에서_제외() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);

        // 1차 업로드
        service.addReceiptsToPage("page-MM", List.of(file("a.jpg", "same".getBytes())), null);
        // 동일 바이트(hash 동일)로 2차 업로드
        AddReceiptsToPageResponse resp =
                service.addReceiptsToPage("page-MM", List.of(file("a.jpg", "same".getBytes())), null);

        assertThat(resp.getTotalAmount()).isEqualTo(25000); // 합산 X
        assertThat(resp.getReceipts().get(0).isDuplicated()).isTrue();
        assertThat(repository.findByNotionPageId("page-MM")).hasSize(1);
    }

    @Test
    void 같은_pageId_에_다른_가게_섞이면_warning() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1, COFFEEBEAN);

        AddReceiptsToPageResponse resp = service.addReceiptsToPage(
                "page-MIX",
                List.of(file("a.jpg", "a".getBytes()), file("b.jpg", "b".getBytes())),
                null);

        assertThat(resp.getWarning()).contains("서로 다른 가게");
        assertThat(resp.getWarning()).contains("마시타야");
        assertThat(resp.getWarning()).contains("커피빈 홍대점");
    }

    @Test
    void 날짜가_없는_영수증은_요청_date_사용() {
        // OCR 결과에 날짜가 없음
        when(ocrService.extractText(any())).thenReturn("""
                마시타야
                블랙라멘 1
                합계 13000원
                """);

        AddReceiptsToPageResponse resp = service.addReceiptsToPage(
                "page-D", List.of(file("a.jpg","a".getBytes())), LocalDate.of(2026,5,3));

        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 5, 3));
    }

    @Test
    void 날짜도_요청도_없으면_오늘_날짜() {
        when(ocrService.extractText(any())).thenReturn("""
                마시타야
                블랙라멘 1
                합계 13000원
                """);

        AddReceiptsToPageResponse resp = service.addReceiptsToPage(
                "page-T", List.of(file("a.jpg","a".getBytes())), null);

        assertThat(resp.getDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void create_page_는_가게가_섞이면_rejected_true_새페이지_생성_안함() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1, COFFEEBEAN);

        CreateReceiptPageResponse resp = service.createPage(
                List.of(file("a.jpg","a".getBytes()), file("b.jpg","b".getBytes())), null);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.isRejected()).isTrue();
        assertThat(resp.getPageId()).isNull();
        verify(notionService, never()).createExpensePage(anyString(), anyInt(), any(), anyString(), anyString());
    }

    @Test
    void add_to_page_는_기존_페이지를_업데이트한다() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);

        service.addReceiptsToPage("page-EXIST", List.of(file("a.jpg","a".getBytes())), null);

        verify(notionService).updateExpensePage(eq("page-EXIST"), anyString(), eq(25000), any(), anyString(), anyString());
        verify(notionService, never()).createExpensePage(anyString(), anyInt(), any(), anyString(), anyString());
    }
}
