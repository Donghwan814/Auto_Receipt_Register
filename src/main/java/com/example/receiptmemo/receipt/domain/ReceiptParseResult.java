package com.example.receiptmemo.receipt.domain;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 영수증 텍스트 파싱 결과.
 */
@Getter
@Builder
public class ReceiptParseResult {

    private String rawText;
    private List<ReceiptItem> items;
    private Integer totalAmount;
    private String memo;

    /** 영수증에서 추출한 날짜 (yyyy-MM-dd). 없으면 null. */
    private String extractedDate;
    /** 영수증에서 추정한 가게명. 없으면 null. */
    private String extractedMerchant;

    private Confidence confidence;

    public enum Confidence { HIGH, MEDIUM, LOW }
}
