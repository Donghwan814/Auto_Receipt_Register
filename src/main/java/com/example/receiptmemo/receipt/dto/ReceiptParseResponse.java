package com.example.receiptmemo.receipt.dto;

import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import com.example.receiptmemo.receipt.domain.ReceiptParseResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 영수증 파싱/업로드 API 의 통합 응답 DTO.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReceiptParseResponse {

    private boolean success;
    private String rawText;
    private List<ReceiptItemResponse> items;
    private Integer totalAmount;
    private String memo;

    /** 영수증에서 추출한 날짜/가게명. */
    private String extractedDate;
    private String extractedMerchant;

    /** 합계 금액과 Notion 금액의 일치 여부 (단일 페이지 비교 시). */
    private Boolean amountMatched;

    /** Notion 메모 업데이트 여부 */
    private Boolean notionUpdated;

    /** 자동 매칭 결과: 후보 리스트와 자동 업데이트 가능 여부. */
    private List<NotionPageCandidateResponse> candidates;
    private Boolean autoUpdateAvailable;

    /** 파싱 신뢰도 */
    private String confidence;

    /**
     * 파싱만 수행한 결과 응답 (Notion 호출 없음).
     *  - notionUpdated, amountMatched 는 응답에서 제외 (null → JsonInclude NON_NULL)
     *  - candidates 는 빈 리스트, autoUpdateAvailable 는 false 로 명시
     */
    public static ReceiptParseResponse parseOnly(ReceiptParseResult result) {
        return baseBuilder(result)
                .candidates(List.of())
                .autoUpdateAvailable(false)
                .build();
    }

    public static ReceiptParseResponse from(ReceiptParseResult result,
                                            Boolean amountMatched,
                                            Boolean notionUpdated) {
        return baseBuilder(result)
                .amountMatched(amountMatched)
                .notionUpdated(notionUpdated)
                .build();
    }

    public static ReceiptParseResponse withCandidates(ReceiptParseResult result,
                                                      List<NotionPageCandidateResponse> candidates,
                                                      boolean autoUpdateAvailable,
                                                      Boolean notionUpdated) {
        return baseBuilder(result)
                .candidates(candidates)
                .autoUpdateAvailable(autoUpdateAvailable)
                .notionUpdated(notionUpdated)
                .build();
    }

    private static ReceiptParseResponseBuilder baseBuilder(ReceiptParseResult result) {
        return ReceiptParseResponse.builder()
                .success(true)
                .rawText(result.getRawText())
                .items(result.getItems().stream().map(ReceiptItemResponse::from).toList())
                .totalAmount(result.getTotalAmount())
                .memo(result.getMemo())
                .extractedDate(result.getExtractedDate())
                .extractedMerchant(result.getExtractedMerchant())
                .confidence(result.getConfidence().name());
    }
}
