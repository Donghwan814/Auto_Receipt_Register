package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Notion 검색으로 얻은 페이지들에 대해 OCR 결과와의 매칭 신뢰도를 산정한다.
 *
 * 규칙
 *  - 날짜·금액 모두 일치             → HIGH
 *  - 금액 일치 + 날짜 ±1일 이내      → MEDIUM
 *  - 날짜만 일치(금액 다름)           → LOW
 *  - 가게명이 비슷하면 1단계 상승 (LOW→MEDIUM, MEDIUM→HIGH)
 *  - 그 외(둘 다 불일치 + 가게명도 다름) → 결과에서 제외
 */
@Component
public class ExpenseMatcher {

    /** 페이지들에 대해 신뢰도를 매겨 반환. 매치되지 않는 것은 빠진다. */
    public List<NotionPageCandidateResponse> score(List<NotionPageCandidateResponse> raw,
                                                   String ocrDate,
                                                   Integer ocrAmount,
                                                   String ocrMerchant) {
        List<NotionPageCandidateResponse> out = new ArrayList<>();
        if (raw == null) return out;

        LocalDate target = parseDate(ocrDate);

        for (NotionPageCandidateResponse c : raw) {
            MatchConfidence conf = scoreOne(c, target, ocrAmount, ocrMerchant);
            if (conf == null) continue;
            out.add(NotionPageCandidateResponse.builder()
                    .pageId(c.getPageId())
                    .title(c.getTitle())
                    .amount(c.getAmount())
                    .date(c.getDate())
                    .confidence(conf.name())
                    .build());
        }

        // HIGH > MEDIUM > LOW 정렬
        out.sort(Comparator.comparingInt(c -> orderOf(c.getConfidence())));
        return out;
    }

    /** 후보가 정확히 1개이고 HIGH 인 경우만 자동 업데이트 가능. */
    public boolean isAutoUpdatable(List<NotionPageCandidateResponse> candidates) {
        return candidates != null
                && candidates.size() == 1
                && "HIGH".equals(candidates.get(0).getConfidence());
    }

    private MatchConfidence scoreOne(NotionPageCandidateResponse c,
                                     LocalDate target,
                                     Integer ocrAmount,
                                     String ocrMerchant) {
        boolean amountEq = ocrAmount != null && Objects.equals(ocrAmount, c.getAmount());
        LocalDate cDate = parseDate(c.getDate());
        Long dayDiff = (target != null && cDate != null) ? Math.abs(target.toEpochDay() - cDate.toEpochDay()) : null;
        boolean dateEq = dayDiff != null && dayDiff == 0L;
        boolean dateNear = dayDiff != null && dayDiff <= 1L;
        boolean merchantSimilar = isMerchantSimilar(ocrMerchant, c.getTitle());

        MatchConfidence base;
        if (amountEq && dateEq) base = MatchConfidence.HIGH;
        else if (amountEq && dateNear) base = MatchConfidence.MEDIUM;
        else if (dateEq && !amountEq) base = MatchConfidence.LOW;
        else if (merchantSimilar) base = MatchConfidence.LOW;
        else return null; // 매칭 실패 - 후보 제외

        return merchantSimilar ? bumpUp(base) : base;
    }

    private MatchConfidence bumpUp(MatchConfidence c) {
        return switch (c) {
            case LOW -> MatchConfidence.MEDIUM;
            case MEDIUM, HIGH -> MatchConfidence.HIGH;
        };
    }

    private int orderOf(String c) {
        if (c == null) return 99;
        return switch (c) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 99;
        };
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // Notion date.start 는 ISO-8601 (yyyy-MM-dd 또는 yyyy-MM-ddTHH:mm:ss...) 가능
            return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 가게명 유사도 판정.
     *  - 한쪽이 다른쪽을 포함하면 true
     *  - 정규화 후 토큰이 절반 이상 겹치면 true
     */
    private boolean isMerchantSimilar(String ocr, String title) {
        if (ocr == null || ocr.isBlank() || title == null || title.isBlank()) return false;
        String a = normalize(ocr);
        String b = normalize(title);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.contains(b) || b.contains(a)) return true;

        Set<String> ta = tokenSet(a);
        Set<String> tb = tokenSet(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        int minSize = Math.min(ta.size(), tb.size());
        return inter.size() * 2 >= minSize; // 절반 이상 토큰 겹침
    }

    /** 이모지/특수문자/괄호/공백 제거 후 소문자화. */
    private String normalize(String s) {
        return s.replaceAll("[\\p{So}\\p{Sk}]", "")    // 이모지
                .replaceAll("\\(.*?\\)", " ")          // 괄호 안 지점명 등
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ") // 특수문자
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private Set<String> tokenSet(String s) {
        Set<String> tokens = new HashSet<>();
        for (String t : s.split("\\s+")) {
            if (t.length() >= 2) tokens.add(t);
        }
        return tokens;
    }
}
