package com.example.receiptmemo.receipt.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * /api/receipts/upload 의 form-data 부가 필드.
 * (실제 파일은 MultipartFile 로 별도 받음.)
 */
@Getter
@Setter
@NoArgsConstructor
public class ReceiptUploadRequest {
    /** 선택: Notion 페이지 ID. */
    private String notionPageId;
}
