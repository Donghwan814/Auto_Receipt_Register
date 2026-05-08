package com.example.receiptmemo.receipt.controller;

import com.example.receiptmemo.receipt.dto.ReceiptParseResponse;
import com.example.receiptmemo.receipt.dto.ReceiptParseTextRequest;
import com.example.receiptmemo.receipt.service.ReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Receipt", description = "영수증 OCR/파싱 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    /**
     * 영수증 이미지 업로드. notionPageId 가 있으면 Notion 메모를 즉시 업데이트한다.
     */
    @Operation(summary = "영수증 이미지 업로드 → OCR → 메모 자동 생성")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptParseResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "notionPageId", required = false) String notionPageId) {
        return receiptService.handleUpload(file, notionPageId);
    }

    /**
     * OCR 텍스트를 직접 입력해서 파싱 결과만 확인. 개발용.
     * Notion 후보 검색·메모 업데이트를 하지 않는다.
     */
    @Operation(summary = "OCR 텍스트 직접 파싱 (개발용 - Notion 미호출)")
    @PostMapping("/parse-text")
    public ReceiptParseResponse parseText(@Valid @RequestBody ReceiptParseTextRequest req) {
        return receiptService.parseOnly(req.getRawText());
    }
}
