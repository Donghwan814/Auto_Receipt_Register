package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import com.example.receiptmemo.notion.service.ExpenseMatcher;
import com.example.receiptmemo.notion.service.NotionService;
import com.example.receiptmemo.receipt.domain.ReceiptParseResult;
import com.example.receiptmemo.receipt.dto.ReceiptParseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

/**
 * 영수증 처리의 유스케이스 진입점.
 *
 * 외부 진입 메서드는 세 가지로 분리되어 있다.
 *  - parseOnly                : OCR 텍스트 파싱만. Notion 호출 없음. (개발 테스트용)
 *  - parseAndUpdateByPageId   : 특정 Notion 페이지 메모 업데이트
 *  - parseAndAutoMatch        : Notion 후보 검색 후 HIGH 단일이면 자동 업데이트
 *
 * 업로드 진입은 handleUpload 가 OCR 후 위 메서드 중 적절한 것을 디스패치한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final ReceiptParser parser;
    private final OcrService ocrService;
    private final NotionService notionService;
    private final ExpenseMatcher expenseMatcher;

    // ---------- 진입 메서드 ----------

    /**
     * 텍스트 파싱만 수행한다. Notion 후보 검색·업데이트를 일절 하지 않는다.
     * /api/receipts/parse-text 전용.
     */
    public ReceiptParseResponse parseOnly(String rawText) {
        ReceiptParseResult result = safeParse(rawText);
        return ReceiptParseResponse.parseOnly(result);
    }

    /**
     * 파싱 후 명시된 Notion 페이지의 메모를 업데이트한다 (+ 금액 일치 비교).
     */
    public ReceiptParseResponse parseAndUpdateByPageId(String rawText, String notionPageId) {
        if (notionPageId == null || notionPageId.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "notionPageId 가 비어 있습니다.");
        }
        ReceiptParseResult result = safeParse(rawText);
        return updateExplicitPage(result, notionPageId);
    }

    /**
     * 파싱 후 후보를 검색해 HIGH 단일이면 자동으로 메모를 업데이트한다.
     */
    public ReceiptParseResponse parseAndAutoMatch(String rawText) {
        ReceiptParseResult result = safeParse(rawText);
        return autoMatch(result);
    }

    /**
     * 영수증 이미지 업로드 처리.
     *  - notionPageId 가 있으면 parseAndUpdateByPageId 흐름
     *  - 없으면 parseAndAutoMatch 흐름
     */
    public ReceiptParseResponse handleUpload(MultipartFile image, String notionPageId) {
        if (image == null || image.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "image 파일이 비어 있습니다.");
        }
        String text;
        try {
            text = ocrService.extractText(image);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("OCR error", e);
            throw new CustomException(ErrorCode.OCR_FAILED, e.getMessage());
        }

        if (notionPageId != null && !notionPageId.isBlank()) {
            return parseAndUpdateByPageId(text, notionPageId);
        }
        return parseAndAutoMatch(text);
    }

    // ---------- 내부 ----------

    private ReceiptParseResult safeParse(String rawText) {
        try {
            return parser.parse(rawText);
        } catch (Exception e) {
            log.error("Parser error", e);
            throw new CustomException(ErrorCode.PARSE_FAILED, e.getMessage());
        }
    }

    private ReceiptParseResponse updateExplicitPage(ReceiptParseResult result, String pageId) {
        Boolean amountMatched = null;
        Boolean notionUpdated;

        try {
            Integer notionAmount = notionService.getAmount(pageId);
            if (notionAmount != null && result.getTotalAmount() != null) {
                amountMatched = Objects.equals(notionAmount, result.getTotalAmount());
            }
        } catch (Exception e) {
            log.warn("Notion 금액 조회 실패 - 비교 생략: {}", e.getMessage());
        }

        try {
            notionService.updateMemo(pageId, result.getMemo());
            notionUpdated = true;
        } catch (Exception e) {
            log.warn("Notion 메모 업데이트 실패: {}", e.getMessage());
            notionUpdated = false;
        }
        return ReceiptParseResponse.from(result, amountMatched, notionUpdated);
    }

    private ReceiptParseResponse autoMatch(ReceiptParseResult result) {
        List<NotionPageCandidateResponse> scored = List.of();
        Boolean notionUpdated = null;

        try {
            List<NotionPageCandidateResponse> raw = notionService.findCandidates(
                    result.getExtractedDate(),
                    result.getTotalAmount(),
                    result.getExtractedMerchant());
            scored = expenseMatcher.score(raw,
                    result.getExtractedDate(),
                    result.getTotalAmount(),
                    result.getExtractedMerchant());
        } catch (Exception e) {
            log.warn("Notion 후보 검색 실패: {}", e.getMessage());
        }

        boolean autoUpdatable = expenseMatcher.isAutoUpdatable(scored);
        if (autoUpdatable) {
            String pageId = scored.get(0).getPageId();
            try {
                notionService.updateMemo(pageId, result.getMemo());
                notionUpdated = true;
                log.info("[AutoMatch] HIGH 단일 후보 메모 자동 업데이트. pageId={}", pageId);
            } catch (Exception e) {
                log.warn("자동 메모 업데이트 실패: {}", e.getMessage());
                notionUpdated = false;
            }
        }
        return ReceiptParseResponse.withCandidates(result, scored, autoUpdatable, notionUpdated);
    }
}
