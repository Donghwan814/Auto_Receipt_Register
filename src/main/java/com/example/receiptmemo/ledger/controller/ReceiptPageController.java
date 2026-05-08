package com.example.receiptmemo.ledger.controller;

import com.example.receiptmemo.ledger.dto.AddReceiptsToPageResponse;
import com.example.receiptmemo.ledger.dto.CreateReceiptPageResponse;
import com.example.receiptmemo.ledger.dto.ReceiptPreviewResponse;
import com.example.receiptmemo.ledger.service.ReceiptPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * Notion 가계부 페이지 단위 영수증 워크플로우.
 *  POST /api/receipts/preview                       - Notion 미반영 분석
 *  POST /api/receipts/create-page                   - 새 Notion 페이지 생성
 *  POST /api/receipts/add-to-page                   - 기존 페이지에 영수증 추가
 *  PATCH /api/receipts/pages/{pageId}/recalculate   - 저장된 영수증으로 재집계 후 Notion 갱신
 */
@Tag(name = "ReceiptPage", description = "Notion 가계부 페이지 워크플로우")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/receipts")
public class ReceiptPageController {

    private final ReceiptPageService service;

    @Operation(summary = "여러 영수증 OCR 미리보기 (Notion 미반영)")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptPreviewResponse preview(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.preview(files, date);
    }

    @Operation(summary = "새 Notion 페이지 생성")
    @PostMapping(value = "/create-page", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CreateReceiptPageResponse createPage(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.createPage(files, date);
    }

    @Operation(summary = "기존 Notion 페이지에 영수증 추가 등록")
    @PostMapping(value = "/add-to-page", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddReceiptsToPageResponse addToPage(
            @RequestParam("notionPageId") String notionPageId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.addReceiptsToPage(notionPageId, files, date);
    }

    @Operation(summary = "특정 페이지의 저장된 영수증 기준으로 Notion 재계산")
    @PatchMapping("/pages/{pageId}/recalculate")
    public AddReceiptsToPageResponse recalculate(
            @PathVariable String pageId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.recalculate(pageId, date);
    }
}
