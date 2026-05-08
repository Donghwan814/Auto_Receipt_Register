package com.example.receiptmemo.receipt.controller;

import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import com.example.receiptmemo.notion.service.NotionService;
import com.example.receiptmemo.receipt.service.OcrService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ocr.engine=mock",
        "notion.api-key=test",
        "notion.database-id=test",
        "spring.datasource.url=jdbc:h2:mem:rcttest;MODE=LEGACY;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class ReceiptControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @MockBean NotionService notionService;
    @MockBean OcrService ocrService;

    @Test
    void parseText_API_는_Notion을_호출하지_않는다() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "rawText", "마시타야\n2026년 5월 3일\n블랙라멘 1\n시오라멘 1\n공기밥 1\n합계 25,000원"
        ));

        mvc.perform(post("/api/receipts/parse-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.memo").value("블랙라멘 + 시오라멘 + 공기밥"))
                .andExpect(jsonPath("$.totalAmount").value(25000))
                .andExpect(jsonPath("$.extractedDate").value("2026-05-03"))
                .andExpect(jsonPath("$.extractedMerchant").value("마시타야"))
                .andExpect(jsonPath("$.confidence").value("HIGH"))
                .andExpect(jsonPath("$.autoUpdateAvailable").value(false))
                .andExpect(jsonPath("$.notionUpdated").doesNotExist())
                .andExpect(jsonPath("$.candidates.length()").value(0));

        // Notion 은 어떤 메서드도 호출되면 안 된다.
        verifyNoInteractions(notionService);
    }

    @Test
    void upload_API_는_HIGH_단일후보면_자동_메모_업데이트() throws Exception {
        // OCR Mock: 라멘 영수증 텍스트
        when(ocrService.extractText(any())).thenReturn(
                "마시타야\n2026년 5월 3일\n블랙라멘 1\n시오라멘 1\n공기밥 1\n합계 25,000원");

        // Notion 후보 검색: 일치하는 페이지 1건 → HIGH 가 되어야 함
        when(notionService.findCandidates(eq("2026-05-03"), eq(25000), anyString()))
                .thenReturn(List.of(NotionPageCandidateResponse.builder()
                        .pageId("page-1")
                        .title("🍜 마시타야 (홍대)")
                        .amount(25000)
                        .date("2026-05-03")
                        .build()));

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", "image/jpeg", "fake-bytes".getBytes());

        mvc.perform(multipart("/api/receipts/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.autoUpdateAvailable").value(true))
                .andExpect(jsonPath("$.notionUpdated").value(true))
                .andExpect(jsonPath("$.candidates[0].confidence").value("HIGH"));

        verify(notionService).updateMemo(eq("page-1"), eq("블랙라멘 + 시오라멘 + 공기밥"));
    }

    @Test
    void upload_API_는_notionPageId_가_있으면_그_페이지를_업데이트() throws Exception {
        when(ocrService.extractText(any())).thenReturn(
                "블랙라멘 1\n시오라멘 1\n합계 13000원");
        when(notionService.getAmount("explicit-page")).thenReturn(13000);

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", "image/jpeg", "x".getBytes());

        mvc.perform(multipart("/api/receipts/upload")
                        .file(file)
                        .param("notionPageId", "explicit-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notionUpdated").value(true))
                .andExpect(jsonPath("$.amountMatched").value(true));

        verify(notionService).updateMemo(eq("explicit-page"), anyString());
        verify(notionService, never()).findCandidates(any(), any(), any());
    }
}
