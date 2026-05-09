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
        when(notionService.createExpensePage(anyString(), anyInt(), any(), anyString(), anyString(), anyString())).thenReturn("new-page-1");

        CreateReceiptPageResponse resp = service.createPage(List.of(file("a.jpg", "a".getBytes())), null);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getPageId()).isEqualTo("new-page-1");
        assertThat(resp.getTitle()).isEqualTo("마시타야");
        assertThat(resp.getTitle()).doesNotContain("🍜");
        assertThat(resp.getTotalAmount()).isEqualTo(25000);
        assertThat(resp.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥");
        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 5, 3));

        verify(notionService).createExpensePage(eq("마시타야"), eq(25000), eq(LocalDate.of(2026,5,3)),
                eq("식비"), eq("블랙라멘 + 시오라멘 + 공기밥"), eq("🍜"));
        verify(notionService).updateExpensePage(eq("new-page-1"), anyString(), anyInt(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void 같은_pageId_에_영수증_2개_금액_메모_합산() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1, MASIRAMEN_2);

        AddReceiptsToPageResponse resp = service.addReceiptsToPage(
                "page-MM",
                List.of(file("a.jpg", "a".getBytes()), file("b.jpg", "b".getBytes())),
                null);

        assertThat(resp.getTotalAmount()).isEqualTo(33500);
        assertThat(resp.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥 + 제로콜라 + 교자");
        assertThat(resp.getTitle()).isEqualTo("마시타야");
        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(resp.getReceipts()).hasSize(2);
        assertThat(resp.getReceipts()).allMatch(r -> !r.isDuplicated());

        verify(notionService).updateExpensePage(eq("page-MM"), eq("마시타야"), eq(33500),
                eq(LocalDate.of(2026,5,3)), eq("식비"),
                eq("블랙라멘 + 시오라멘 + 공기밥 + 제로콜라 + 교자"), eq("🍜"));
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
        verify(notionService, never()).createExpensePage(anyString(), anyInt(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void add_to_page_는_기존_페이지를_업데이트한다() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);

        service.addReceiptsToPage("page-EXIST", List.of(file("a.jpg","a".getBytes())), null);

        verify(notionService).updateExpensePage(eq("page-EXIST"), anyString(), eq(25000), any(), anyString(), anyString(), anyString());
        verify(notionService, never()).createExpensePage(anyString(), anyInt(), any(), anyString(), anyString(), anyString());
    }

    // ---------- resync ----------

    @Test
    void resync_같은_페이지에_영수증_2개_금액_합산() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1, MASIRAMEN_2);

        AddReceiptsToPageResponse resp = service.resyncReceiptsForPage(
                "page-RS",
                List.of(file("a.jpg","a".getBytes()), file("b.jpg","b".getBytes())),
                null);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getTotalAmount()).isEqualTo(33500);
        assertThat(resp.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥 + 제로콜라 + 교자");
        assertThat(repository.findByNotionPageId("page-RS")).hasSize(2);
        verify(notionService).updateExpensePage(eq("page-RS"), eq("마시타야"), eq(33500),
                any(), eq("식비"), anyString(), eq("🍜"));
    }

    @Test
    void resync_같은_이미지_2개는_1개만_반영() {
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);

        AddReceiptsToPageResponse resp = service.resyncReceiptsForPage(
                "page-DUP",
                List.of(file("a.jpg","same".getBytes()), file("b.jpg","same".getBytes())),
                null);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getTotalAmount()).isEqualTo(25000);
        assertThat(repository.findByNotionPageId("page-DUP")).hasSize(1);
        // 두 번째는 duplicated=true 로 표시
        long dupCount = resp.getReceipts().stream().filter(r -> r.isDuplicated()).count();
        assertThat(dupCount).isEqualTo(1);
    }

    @Test
    void resync_기존_DB_A를_지우고_새_B만_반영() {
        // 1단계: 기존에 영수증 A (마시타야, 25000) 가 DB 에 있는 상태
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);
        service.addReceiptsToPage("page-SW", List.of(file("a.jpg", "a".getBytes())), null);
        assertThat(repository.findByNotionPageId("page-SW")).hasSize(1);

        // 2단계: Notion 에서 A 를 삭제하고 B (커피빈, 18900) 만 새로 올린 상태로 resync
        reset(notionService);
        when(ocrService.extractText(any())).thenReturn(COFFEEBEAN);

        AddReceiptsToPageResponse resp = service.resyncReceiptsForPage(
                "page-SW", List.of(file("b.jpg", "b".getBytes())), null);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getTotalAmount()).isEqualTo(18900); // A 합산 안 됨
        java.util.List<com.example.receiptmemo.ledger.persistence.ProcessedReceipt> rows =
                repository.findByNotionPageId("page-SW");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMerchant()).isEqualTo("커피빈 홍대점");
        verify(notionService).updateExpensePage(eq("page-SW"), eq("커피빈 홍대점"), eq(18900),
                any(), anyString(), anyString(), anyString());
    }

    @Test
    void resync_이미지_0개면_Notion_페이지를_덮어쓰지_않음() {
        AddReceiptsToPageResponse resp = service.resyncReceiptsForPage(
                "page-EMPTY", java.util.List.of(), null);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getReason()).isEqualTo("이미지 없음");
        verifyNoInteractions(notionService);
        // null files 도 동일
        AddReceiptsToPageResponse resp2 = service.resyncReceiptsForPage("page-EMPTY", null, null);
        assertThat(resp2.isSuccess()).isFalse();
        assertThat(resp2.getReason()).isEqualTo("이미지 없음");
    }

    @Test
    void resync_후_processed_receipt에는_현재_이미지_fileHash만_남음() {
        // 기존: A (마시타야)
        when(ocrService.extractText(any())).thenReturn(MASIRAMEN_1);
        service.addReceiptsToPage("page-H", List.of(file("a.jpg", "a".getBytes())), null);
        String oldHash = repository.findByNotionPageId("page-H").get(0).getFileHash();

        // resync 로 B (커피빈) 만 새로 반영
        when(ocrService.extractText(any())).thenReturn(COFFEEBEAN);
        service.resyncReceiptsForPage("page-H", List.of(file("b.jpg", "different-bytes".getBytes())), null);

        java.util.List<com.example.receiptmemo.ledger.persistence.ProcessedReceipt> rows =
                repository.findByNotionPageId("page-H");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getFileHash()).isNotEqualTo(oldHash);
    }

    // ---------- OCR 실패 가드 ----------

    @Test
    void resync_모든_OCR이_실패하면_Notion_업데이트_안함() {
        when(ocrService.extractText(any()))
                .thenThrow(new com.example.receiptmemo.global.exception.CustomException(
                        com.example.receiptmemo.global.exception.ErrorCode.OCR_FAILED, "vision down"));

        AddReceiptsToPageResponse resp = service.resyncReceiptsForPage(
                "page-FAIL",
                List.of(file("a.jpg","a".getBytes()), file("b.jpg","b".getBytes())),
                null);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getReason()).isEqualTo("OCR_FAILED");
        verify(notionService, never()).updateExpensePage(anyString(), anyString(), anyInt(),
                any(), anyString(), anyString(), anyString());
        // 가게 미상/0/공백 으로 덮어쓰지 않았는지 확인
        verify(notionService, never()).updateExpensePage(eq("page-FAIL"), eq("(가게 미상)"),
                eq(0), any(), anyString(), anyString(), anyString());
    }

    @Test
    void resync_성공_1개_실패_1개면_성공한_1개만_반영() {
        // 1번째 호출: 실패, 2번째 호출: 성공
        when(ocrService.extractText(any()))
                .thenThrow(new com.example.receiptmemo.global.exception.CustomException(
                        com.example.receiptmemo.global.exception.ErrorCode.OCR_FAILED, "transient"))
                .thenReturn(MASIRAMEN_1);

        AddReceiptsToPageResponse resp = service.resyncReceiptsForPage(
                "page-MIX2",
                List.of(file("bad.jpg","bad".getBytes()), file("good.jpg","good".getBytes())),
                null);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getTotalAmount()).isEqualTo(25000);
        assertThat(repository.findByNotionPageId("page-MIX2")).hasSize(1);
        verify(notionService).updateExpensePage(eq("page-MIX2"), eq("마시타야"), eq(25000),
                any(), anyString(), anyString(), anyString());
    }
}
