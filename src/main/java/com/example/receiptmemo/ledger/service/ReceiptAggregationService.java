package com.example.receiptmemo.ledger.service;

import com.example.receiptmemo.ledger.domain.ReceiptPageAggregate;
import com.example.receiptmemo.ledger.persistence.ProcessedReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 한 페이지에 누적된 영수증들 → 최종 집계.
 *
 * 규칙
 *  - amount  : 모든 row 의 amount 합계 (null 은 0 취급)
 *  - date    : row 들 중 가장 이른 receiptDate. 모두 null 이면 fallbackDate, 그것도 null 이면 오늘
 *  - merchant: 가장 빈도 높은 merchant. 동률이면 가장 먼저 등장한 것
 *  - title   : EmojiTitleService.build(merchant)
 *  - memo    : 모든 row 의 memo 를 " + " 로 합치되, 중복 메뉴는 제거 (등장 순서 유지)
 *  - warning : 서로 다른 merchant 가 2종 이상이면 안내 메시지
 */
@Service
@RequiredArgsConstructor
public class ReceiptAggregationService {

    private final EmojiTitleService emojiTitleService;
    private final CategoryClassifier categoryClassifier;

    public ReceiptPageAggregate aggregate(List<ProcessedReceipt> rows, LocalDate fallbackDate) {
        if (rows == null) rows = List.of();

        int amount = rows.stream()
                .map(ProcessedReceipt::getAmount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        LocalDate date = rows.stream()
                .map(ProcessedReceipt::getReceiptDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(fallbackDate != null ? fallbackDate : LocalDate.now());

        String merchant = pickMerchant(rows);
        String memo = mergeMemos(rows);
        String title = emojiTitleService.build(merchant, memo);
        String warning = detectMixedMerchantWarning(rows);
        String category = categoryClassifier.classify(merchant, memo);

        return ReceiptPageAggregate.builder()
                .title(title)
                .merchant(merchant)
                .amount(amount)
                .date(date)
                .memo(memo)
                .warning(warning)
                .category(category)
                .build();
    }

    /** 빈도 높은 merchant. 동률이면 첫 등장. */
    private String pickMerchant(List<ProcessedReceipt> rows) {
        LinkedHashMap<String, Integer> count = new LinkedHashMap<>();
        for (ProcessedReceipt r : rows) {
            String m = r.getMerchant();
            if (m == null || m.isBlank()) continue;
            count.merge(m, 1, Integer::sum);
        }
        return count.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** "메뉴1 + 메뉴2 + ..." 들을 모아 dedupe (등장 순서 유지). */
    String mergeMemos(List<ProcessedReceipt> rows) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (ProcessedReceipt r : rows) {
            String memo = r.getMemo();
            if (memo == null || memo.isBlank()) continue;
            for (String t : memo.split("\\s*\\+\\s*")) {
                String token = t.trim();
                if (!token.isEmpty()) tokens.add(token);
            }
        }
        return String.join(" + ", tokens);
    }

    private String detectMixedMerchantWarning(List<ProcessedReceipt> rows) {
        Set<String> merchants = new LinkedHashSet<>();
        for (ProcessedReceipt r : rows) {
            if (r.getMerchant() != null && !r.getMerchant().isBlank()) {
                merchants.add(r.getMerchant());
            }
        }
        if (merchants.size() <= 1) return null;
        return "서로 다른 가게명이 감지되었습니다: " + String.join(", ", merchants)
                + ". 이 페이지는 한 가게에 대한 항목이어야 합니다.";
    }
}
