package com.example.receiptmemo.notion.controller;

import com.example.receiptmemo.notion.dto.NotionMemoUpdateRequest;
import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import com.example.receiptmemo.notion.service.ExpenseMatcher;
import com.example.receiptmemo.notion.service.NotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Notion", description = "Notion 가계부 연동")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notion")
public class NotionController {

    private final NotionService notionService;
    private final ExpenseMatcher expenseMatcher;

    /** 메모 컬럼 수동 업데이트. */
    @Operation(summary = "Notion 페이지의 메모 컬럼 업데이트")
    @PatchMapping("/pages/{pageId}/memo")
    public Map<String, Object> updateMemo(@PathVariable String pageId,
                                          @Valid @RequestBody NotionMemoUpdateRequest req) {
        notionService.updateMemo(pageId, req.getMemo());
        return Map.of("success", true, "pageId", pageId);
    }

    /**
     * 가계부 후보 검색 + 매칭 신뢰도 부여.
     * 자동 업데이트 가능 여부(autoUpdateAvailable)를 함께 반환한다.
     */
    @Operation(summary = "가계부 후보 페이지 검색 (날짜±1일 + 금액 + 가게명)")
    @GetMapping("/expenses/candidates")
    public Map<String, Object> getCandidates(@RequestParam(required = false) String date,
                                             @RequestParam(required = false) Integer amount,
                                             @RequestParam(required = false) String merchant) {
        List<NotionPageCandidateResponse> raw = notionService.findCandidates(date, amount, merchant);
        List<NotionPageCandidateResponse> scored = expenseMatcher.score(raw, date, amount, merchant);
        return Map.of(
                "success", true,
                "candidates", scored,
                "autoUpdateAvailable", expenseMatcher.isAutoUpdatable(scored)
        );
    }
}
