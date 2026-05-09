package com.example.receiptmemo.ledger.service;

import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import com.example.receiptmemo.ledger.domain.ReceiptAnalysisResult;
import com.example.receiptmemo.ledger.domain.ReceiptPageAggregate;
import com.example.receiptmemo.ledger.dto.AddReceiptsToPageResponse;
import com.example.receiptmemo.ledger.dto.CreateReceiptPageResponse;
import com.example.receiptmemo.ledger.dto.ReceiptAnalysisResponse;
import com.example.receiptmemo.ledger.dto.ReceiptPreviewResponse;
import com.example.receiptmemo.ledger.persistence.ProcessedReceipt;
import com.example.receiptmemo.ledger.persistence.ProcessedReceiptRepository;
import com.example.receiptmemo.notion.service.NotionService;
import com.example.receiptmemo.receipt.domain.ReceiptParseResult;
import com.example.receiptmemo.receipt.service.OcrService;
import com.example.receiptmemo.receipt.service.ReceiptParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.*;

/**
 * 영수증 → Notion 가계부 페이지 워크플로우.
 *
 * 핵심 원칙
 *  - 같은 notionPageId 안에서만 합산한다 (가게가 다르면 다른 페이지).
 *  - 같은 (pageId, fileHash) 조합은 중복 → 합산에서 제외.
 *  - 집계는 항상 DB 에 저장된 row 전체를 다시 계산해서 Notion 에 반영.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptPageService {

    private final OcrService ocrService;
    private final ReceiptParser parser;
    private final ProcessedReceiptRepository repository;
    private final ReceiptDuplicateChecker duplicateChecker;
    private final ReceiptAggregationService aggregationService;
    private final NotionService notionService;

    // ---------- preview ----------

    /** 분석만 수행. DB/Notion 에 어떤 변경도 가하지 않는다. */
    public ReceiptPreviewResponse preview(List<MultipartFile> files, LocalDate fallbackDate) {
        ensureFiles(files);
        List<ReceiptAnalysisResult> analyzed = files.stream()
                .map(f -> analyze(f, null, fallbackDate))
                .toList();
        return ReceiptPreviewResponse.builder()
                .success(true)
                .receipts(analyzed.stream().map(ReceiptAnalysisResponse::from).toList())
                .build();
    }

    // ---------- add to existing page ----------

    /**
     * 기존 Notion 페이지에 영수증을 추가 등록.
     *  - 새 영수증이면 저장
     *  - 중복이면 저장 X / duplicated=true
     *  - 페이지에 저장된 전체 row 로 재집계 → Notion 업데이트
     */
    @Transactional
    public AddReceiptsToPageResponse addReceiptsToPage(String notionPageId,
                                                       List<MultipartFile> files,
                                                       LocalDate fallbackDate) {
        if (notionPageId == null || notionPageId.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "notionPageId 가 비어 있습니다.");
        }
        ensureFiles(files);

        List<ReceiptAnalysisResult> analyzed = new ArrayList<>();
        for (MultipartFile f : files) {
            ReceiptAnalysisResult r = analyze(f, notionPageId, fallbackDate);
            if (r.isOk() && !r.isDuplicated()) {
                ProcessedReceipt saved = save(notionPageId, r);
                log.info("[AddToPage] saved receipt id={}, hash={}", saved.getId(), saved.getFileHash());
            }
            analyzed.add(r);
        }

        ReceiptPageAggregate agg = recalculateAndPushToNotion(notionPageId, fallbackDate, /*create=*/false);
        List<String> warnings = collectMixedMerchantWarnings(notionPageId, agg);

        return AddReceiptsToPageResponse.builder()
                .success(true)
                .pageId(notionPageId)
                .title(agg.getTitle())
                .totalAmount(agg.getAmount())
                .date(agg.getDate())
                .memo(agg.getMemo())
                .warning(agg.getWarning())
                .warnings(warnings.isEmpty() ? null : warnings)
                .receipts(analyzed.stream().map(ReceiptAnalysisResponse::from).toList())
                .build();
    }

    /** 페이지에 누적된 row 들의 merchant 가 2종 이상이면 WARN 로그 + warnings 메시지. */
    private List<String> collectMixedMerchantWarnings(String pageId, ReceiptPageAggregate agg) {
        java.util.List<ProcessedReceipt> rows = repository.findByNotionPageIdOrderByCreatedAtAsc(pageId);
        java.util.LinkedHashSet<String> merchants = new java.util.LinkedHashSet<>();
        for (ProcessedReceipt r : rows) {
            if (r.getMerchant() != null && !r.getMerchant().isBlank()) merchants.add(r.getMerchant());
        }
        if (merchants.size() <= 1) return java.util.Collections.emptyList();
        log.warn("[ReceiptPageService] 같은 페이지에 다수 가게가 섞여 있음. pageId={}, merchants={}, count={}",
                pageId, merchants, merchants.size());
        java.util.List<String> w = new java.util.ArrayList<>();
        if (agg.getWarning() != null) w.add(agg.getWarning());
        return w;
    }

    // ---------- resync (source-of-truth: current page images) ----------

    /**
     * 현재 Notion 페이지에 존재하는 이미지들을 source of truth 로 보고,
     * 해당 페이지의 processed_receipt 기록을 모두 지우고 다시 OCR/저장/집계한다.
     *
     * 호출자가 webhook/수동 처리 등에서 comments + blocks 의 현재 이미지를 모아
     * 넘겨주면, 사용자가 영수증을 삭제/교체한 경우에도 안정적으로 동기화된다.
     *
     * 안전장치:
     *  - files 가 비어있으면 Notion 값을 0/공백으로 덮어쓰지 않고 success=false 로 반환.
     *  - 동일 fileHash 는 1번만 저장 (duplicated=true 표시).
     */
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public AddReceiptsToPageResponse resyncReceiptsForPage(String notionPageId,
                                                           List<MultipartFile> files,
                                                           LocalDate fallbackDate) {
        if (notionPageId == null || notionPageId.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "notionPageId 가 비어 있습니다.");
        }

        if (files == null || files.isEmpty()) {
            log.info("[Resync] 현재 이미지 없음 → Notion 페이지 유지. pageId={}", notionPageId);
            return AddReceiptsToPageResponse.builder()
                    .success(false)
                    .pageId(notionPageId)
                    .reason("이미지 없음")
                    .warning("현재 페이지에 이미지가 없어 Notion 값을 유지합니다.")
                    .build();
        }

        // 1) 기존 row 전부 삭제 (DB 동기화의 시작점)
        long deleted = repository.deleteByNotionPageId(notionPageId);
        log.info("[Resync] 기존 processed_receipt 삭제. pageId={}, deleted={}", notionPageId, deleted);

        // 2) 분석 + fileHash 기준 batch dedupe + DB 중복 방어
        Set<String> seenHashes = new LinkedHashSet<>();
        List<ReceiptAnalysisResult> analyzed = new ArrayList<>();
        int okCount = 0;
        int errorCount = 0;
        for (MultipartFile f : files) {
            ReceiptAnalysisResult r = analyze(f, /*notionPageId=*/null, fallbackDate);
            String h = r.getFileHash();
            if (h != null && !seenHashes.add(h)) {
                // 같은 request 안에 같은 fileHash → duplicated 로 마킹만 하고 저장 X
                analyzed.add(r.toBuilder().duplicated(true).build());
                log.info("[Resync] request duplicate fileHash skip. hash={}", h);
                continue;
            }
            if (!r.isOk()) {
                errorCount++;
                log.warn("[Resync] 영수증 분석 실패 → row 저장/집계에서 제외. error={}", r.getError());
                analyzed.add(r);
                continue;
            }
            // DB 에 이미 같은 (pageId, fileHash) 가 있으면 (race condition 등) duplicated 처리.
            if (h != null && repository.existsByNotionPageIdAndFileHash(notionPageId, h)) {
                log.info("[Resync] db duplicate fileHash skip. pageId={}, hash={}", notionPageId, h);
                analyzed.add(r.toBuilder().duplicated(true).build());
                continue;
            }
            // resync 는 step (1) 에서 같은 pageId 의 row 를 모두 delete 했고 page lock 으로
            // 동시 처리가 차단되어 있으므로, 이 시점의 save 는 충돌이 날 일이 없다.
            // 그래도 race 에 대비해 DataIntegrityViolation 은 duplicated=true 로 흡수.
            try {
                ProcessedReceipt saved = save(notionPageId, r);
                log.info("[Resync] saved receipt id={}, hash={}", saved.getId(), saved.getFileHash());
                okCount++;
                analyzed.add(r);
            } catch (DataIntegrityViolationException dup) {
                log.info("[Resync] db duplicate fileHash skip (race). pageId={}, hash={}", notionPageId, h);
                analyzed.add(r.toBuilder().duplicated(true).build());
            }
        }

        // 3) 모든 영수증이 OCR/파싱 실패라면 Notion 페이지를 덮어쓰지 않는다.
        if (okCount == 0) {
            String reason = errorCount > 0 ? "OCR_FAILED" : "NO_VALID_RECEIPTS";
            log.warn("[ReceiptPageService] Notion update skipped. reason={}, pageId={}, errorCount={}",
                    reason, notionPageId, errorCount);
            return AddReceiptsToPageResponse.builder()
                    .success(false)
                    .pageId(notionPageId)
                    .reason(reason)
                    .warning("OCR 결과가 없어 Notion 페이지를 업데이트하지 않습니다.")
                    .receipts(analyzed.stream().map(ReceiptAnalysisResponse::from).toList())
                    .build();
        }

        // 4) 재집계 + Notion 업데이트
        ReceiptPageAggregate agg = recalculateAndPushToNotion(notionPageId, fallbackDate, /*create=*/false);
        List<String> warnings = collectMixedMerchantWarnings(notionPageId, agg);

        return AddReceiptsToPageResponse.builder()
                .success(true)
                .pageId(notionPageId)
                .title(agg.getTitle())
                .totalAmount(agg.getAmount())
                .date(agg.getDate())
                .memo(agg.getMemo())
                .warning(agg.getWarning())
                .warnings(warnings.isEmpty() ? null : warnings)
                .receipts(analyzed.stream().map(ReceiptAnalysisResponse::from).toList())
                .build();
    }

    // ---------- create new page ----------

    /**
     * 새 Notion 페이지 생성. 여러 영수증이 들어와도 모두 같은 가게여야만 한다.
     * 가게가 섞여 있으면 페이지를 만들지 않고 rejected=true 로 반환.
     */
    @Transactional
    public CreateReceiptPageResponse createPage(List<MultipartFile> files, LocalDate fallbackDate) {
        ensureFiles(files);

        List<ReceiptAnalysisResult> analyzed = new ArrayList<>();
        for (MultipartFile f : files) {
            analyzed.add(analyze(f, null, fallbackDate));
        }

        // 성공한 영수증 중 merchant 종류 검사
        Set<String> merchants = new LinkedHashSet<>();
        for (ReceiptAnalysisResult r : analyzed) {
            if (r.isOk() && r.getMerchant() != null && !r.getMerchant().isBlank()) {
                merchants.add(r.getMerchant());
            }
        }
        if (merchants.size() > 1) {
            return CreateReceiptPageResponse.builder()
                    .success(false)
                    .rejected(true)
                    .warning("서로 다른 가게가 감지되었습니다: " + String.join(", ", merchants)
                            + ". 가게별로 별도 페이지를 만들어야 합니다.")
                    .receipts(analyzed.stream().map(ReceiptAnalysisResponse::from).toList())
                    .build();
        }

        // 빈 임시 집계로 일단 페이지 생성에 필요한 항목/메모/금액/날짜 산출.
        // (Notion 에 페이지를 먼저 만들고 → 그 pageId 로 row 저장 → 재집계 → updateExpensePage)
        List<ProcessedReceipt> tempRows = new ArrayList<>();
        for (ReceiptAnalysisResult r : analyzed) {
            if (r.isOk()) tempRows.add(toEntity("__TEMP__", r));
        }
        ReceiptPageAggregate preAgg = aggregationService.aggregate(tempRows, fallbackDate);

        if (tempRows.isEmpty()) {
            throw new CustomException(ErrorCode.PARSE_FAILED, "분석에 성공한 영수증이 없습니다.");
        }

        String newPageId = notionService.createExpensePage(
                preAgg.getTitle(), preAgg.getAmount(), preAgg.getDate(),
                preAgg.getCategory(), preAgg.getMemo(), preAgg.getIconEmoji());

        // DB 에 영수증 저장 (실제 pageId 로). 같은 파일 hash 가 새 pageId 와 충돌할 일은 없음.
        for (ReceiptAnalysisResult r : analyzed) {
            if (r.isOk() && !r.isDuplicated()) {
                save(newPageId, r);
            }
        }

        // 안전하게 한 번 더 집계해서 Notion 갱신 (idempotent)
        ReceiptPageAggregate finalAgg = recalculateAndPushToNotion(newPageId, fallbackDate, /*create=*/true);

        return CreateReceiptPageResponse.builder()
                .success(true)
                .pageId(newPageId)
                .title(finalAgg.getTitle())
                .totalAmount(finalAgg.getAmount())
                .date(finalAgg.getDate())
                .memo(finalAgg.getMemo())
                .warning(finalAgg.getWarning())
                .receipts(analyzed.stream().map(ReceiptAnalysisResponse::from).toList())
                .build();
    }

    // ---------- recalculate ----------

    /** DB 의 row 들로 다시 집계해서 Notion 페이지를 업데이트. */
    @Transactional
    public AddReceiptsToPageResponse recalculate(String pageId, LocalDate fallbackDate) {
        if (pageId == null || pageId.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "pageId 가 비어 있습니다.");
        }
        List<ProcessedReceipt> rows = repository.findByNotionPageIdOrderByCreatedAtAsc(pageId);
        if (rows.isEmpty()) {
            throw new CustomException(ErrorCode.NOTION_PAGE_NOT_FOUND,
                    "해당 페이지에 저장된 영수증이 없습니다: " + pageId);
        }
        ReceiptPageAggregate agg = aggregationService.aggregate(rows, fallbackDate);
        notionService.updateExpensePage(pageId, agg.getTitle(), agg.getAmount(), agg.getDate(),
                agg.getCategory(), agg.getMemo(), agg.getIconEmoji());

        return AddReceiptsToPageResponse.builder()
                .success(true)
                .pageId(pageId)
                .title(agg.getTitle())
                .totalAmount(agg.getAmount())
                .date(agg.getDate())
                .memo(agg.getMemo())
                .warning(agg.getWarning())
                .receipts(rows.stream().map(this::toAnalysisResponseFromEntity).toList())
                .build();
    }

    // ---------- internals ----------

    private ReceiptPageAggregate recalculateAndPushToNotion(String pageId, LocalDate fallbackDate, boolean justCreated) {
        List<ProcessedReceipt> rows = repository.findByNotionPageIdOrderByCreatedAtAsc(pageId);
        ReceiptPageAggregate agg = aggregationService.aggregate(rows, fallbackDate);

        // 안전장치: row 가 하나도 없거나 merchant/amount/memo 가 모두 비어있으면
        // (가게 미상)/0원 으로 덮어쓰지 않는다. justCreated 는 방금 만든 페이지라 이 경로로 오지 않는다.
        if (!justCreated && isEmptyAggregate(rows, agg)) {
            log.warn("[ReceiptPageService] Notion update skipped. reason=NO_VALID_RECEIPTS, pageId={}", pageId);
            return agg;
        }

        try {
            notionService.updateExpensePage(pageId, agg.getTitle(), agg.getAmount(), agg.getDate(),
                    agg.getCategory(), agg.getMemo(), agg.getIconEmoji());
        } catch (Exception e) {
            if (justCreated) throw e; // 방금 만든 페이지를 갱신하지 못하면 명백한 오류
            log.warn("[ReceiptPageService] Notion 업데이트 실패 (집계는 완료됨): {}", e.getMessage());
        }
        return agg;
    }

    private boolean isEmptyAggregate(List<ProcessedReceipt> rows, ReceiptPageAggregate agg) {
        if (rows == null || rows.isEmpty()) return true;
        boolean noMerchant = agg.getMerchant() == null || agg.getMerchant().isBlank();
        boolean zeroAmount = agg.getAmount() == 0;
        boolean noMemo = agg.getMemo() == null || agg.getMemo().isBlank();
        return noMerchant && zeroAmount && noMemo;
    }

    /** OCR + 파싱 + 해시 + 중복판정 → 분석 결과. 실패해도 예외를 던지지 않는다. */
    ReceiptAnalysisResult analyze(MultipartFile file, String notionPageId, LocalDate fallbackDate) {
        if (file == null || file.isEmpty()) {
            return ReceiptAnalysisResult.builder().error("빈 파일입니다.").build();
        }
        String hash;
        try {
            hash = duplicateChecker.hashOf(file);
        } catch (Exception e) {
            return ReceiptAnalysisResult.builder().error("해시 계산 실패: " + e.getMessage()).build();
        }

        boolean duplicated = notionPageId != null && duplicateChecker.isDuplicate(notionPageId, hash);

        String text;
        try {
            text = ocrService.extractText(file);
        } catch (CustomException e) {
            return ReceiptAnalysisResult.builder()
                    .fileHash(hash).duplicated(duplicated)
                    .error(e.getMessage()).build();
        } catch (Exception e) {
            return ReceiptAnalysisResult.builder()
                    .fileHash(hash).duplicated(duplicated)
                    .error("OCR 실패: " + e.getMessage()).build();
        }

        ReceiptParseResult parsed;
        try {
            parsed = parser.parse(text);
        } catch (Exception e) {
            return ReceiptAnalysisResult.builder()
                    .fileHash(hash).duplicated(duplicated).rawText(text)
                    .error("파싱 실패: " + e.getMessage()).build();
        }

        LocalDate date = toLocalDate(parsed.getExtractedDate());
        if (date == null) date = fallbackDate; // OCR 날짜 없으면 요청 date 보조값

        return ReceiptAnalysisResult.builder()
                .fileHash(hash)
                .duplicated(duplicated)
                .merchant(parsed.getExtractedMerchant())
                .receiptDate(date)
                .amount(parsed.getTotalAmount())
                .memo(parsed.getMemo())
                .rawText(text)
                .build();
    }

    private ProcessedReceipt save(String pageId, ReceiptAnalysisResult r) {
        return repository.save(toEntity(pageId, r));
    }

    private ProcessedReceipt toEntity(String pageId, ReceiptAnalysisResult r) {
        return ProcessedReceipt.builder()
                .notionPageId(pageId)
                .fileHash(r.getFileHash())
                .merchant(r.getMerchant())
                .receiptDate(r.getReceiptDate())
                .amount(r.getAmount())
                .memo(r.getMemo())
                .rawText(r.getRawText())
                .build();
    }

    private ReceiptAnalysisResponse toAnalysisResponseFromEntity(ProcessedReceipt p) {
        return ReceiptAnalysisResponse.builder()
                .merchant(p.getMerchant())
                .amount(p.getAmount())
                .date(p.getReceiptDate())
                .memo(p.getMemo())
                .duplicated(false)
                .build();
    }

    private LocalDate toLocalDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDate.parse(iso); }
        catch (Exception e) { return null; }
    }

    private void ensureFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "files 가 비어 있습니다.");
        }
    }
}
