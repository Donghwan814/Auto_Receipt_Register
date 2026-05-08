package com.example.receiptmemo.receipt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * /api/receipts/parse-text 요청 DTO.
 */
@Getter
@Setter
@NoArgsConstructor
public class ReceiptParseTextRequest {

    @NotBlank(message = "rawText 는 필수입니다.")
    private String rawText;

    /** 선택: Notion 페이지 ID. 있으면 메모를 즉시 업데이트한다. */
    private String notionPageId;
}
