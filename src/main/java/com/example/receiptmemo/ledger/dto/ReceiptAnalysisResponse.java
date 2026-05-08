package com.example.receiptmemo.ledger.dto;

import com.example.receiptmemo.ledger.domain.ReceiptAnalysisResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 영수증 1장 분석 결과 응답 항목.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReceiptAnalysisResponse {
    private String merchant;
    private Integer amount;
    private LocalDate date;
    private String memo;
    private boolean duplicated;
    private String error;

    /** TODO(remove): OCR 파서 튜닝용 디버그 필드. 실제 운영 시 제거 예정. */
    private String rawText;

    public static ReceiptAnalysisResponse from(ReceiptAnalysisResult r) {
        return ReceiptAnalysisResponse.builder()
                .merchant(r.getMerchant())
                .amount(r.getAmount())
                .date(r.getReceiptDate())
                .memo(r.getMemo())
                .duplicated(r.isDuplicated())
                .error(r.getError())
                .rawText(r.getRawText())
                .build();
    }
}
