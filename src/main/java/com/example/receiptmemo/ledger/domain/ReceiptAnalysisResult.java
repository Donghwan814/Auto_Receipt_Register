package com.example.receiptmemo.ledger.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 영수증 1장 분석 결과 (OCR + 파서 통과 후의 표준 형태).
 *  - duplicated : 같은 페이지에 같은 fileHash 가 이미 있으면 true
 *  - error      : OCR/파싱 실패 시 메시지
 */
@Getter
@Builder(toBuilder = true)
public class ReceiptAnalysisResult {
    private String fileHash;
    private String merchant;
    private LocalDate receiptDate;
    private Integer amount;
    private String memo;
    private String rawText;
    private boolean duplicated;
    private String error;

    public boolean isOk() {
        return error == null;
    }
}
