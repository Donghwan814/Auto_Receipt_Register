package com.example.receiptmemo.ledger.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가게명/메모 키워드에 어울리는 이모지를 prefix 로 붙여 Notion 항목 제목을 만든다.
 *
 * 우선순위
 *  1) 주차/교통 키워드 → 🅿️
 *  2) 카페/커피 키워드 → ☕
 *  3) 그 외 매칭 (먼저 등장하는 규칙 우선, merchant → memo 순)
 *  4) 기본값 💳
 */
@Service
public class EmojiTitleService {

    public static final String DEFAULT_EMOJI = "💳";
    public static final String PARKING_EMOJI = "🅿️";
    public static final String CAFE_EMOJI = "☕";

    private static final List<String> PARKING_KW = List.of(
            "주차", "주차장", "파킹"
    );
    private static final List<String> CAFE_KW = List.of(
            "카페", "커피", "스타벅스", "투썸", "커피빈", "메가커피",
            "이디야", "빽다방", "컴포즈", "더벤티"
    );

    /** 그 외 키워드 → 이모지. LinkedHashMap 으로 우선순위 유지. */
    private static final Map<String, List<String>> RULES = new LinkedHashMap<>();
    static {
        RULES.put("🍜", List.of("라멘", "우동", "국수", "면", "마시타야"));
        RULES.put("🥩", List.of("고기", "삼겹살", "갈비", "스테이크"));
        RULES.put("🍗", List.of("치킨", "닭강정"));
        RULES.put("🍕", List.of("피자"));
        RULES.put("🍔", List.of("햄버거", "버거", "맥도날드", "버거킹", "롯데리아"));
        RULES.put("🎬", List.of("영화", "CGV", "롯데시네마", "메가박스"));
        RULES.put("🏪", List.of("편의점", "CU", "GS25", "세븐일레븐"));
        RULES.put("🛍️", List.of("다이소", "올리브영", "쇼핑", "구매"));
    }

    /** "마시타야" → "🍜 마시타야" (merchant 만으로 결정). */
    public String build(String merchant) {
        return build(merchant, null);
    }

    /** merchant + memo 컨텍스트로 제목 생성. memo 는 fallback 매칭에만 사용. */
    public String build(String merchant, String memo) {
        String name = (merchant == null || merchant.isBlank()) ? "(가게 미상)" : merchant.trim();
        return resolveEmoji(merchant, memo) + " " + name;
    }

    public String resolveEmoji(String merchant) {
        return resolveEmoji(merchant, null);
    }

    public String resolveEmoji(String merchant, String memo) {
        // 1) 주차/교통 우선
        if (containsAny(merchant, PARKING_KW) || containsAny(memo, PARKING_KW)) {
            return PARKING_EMOJI;
        }
        // 2) 카페/커피 우선
        if (containsAny(merchant, CAFE_KW) || containsAny(memo, CAFE_KW)) {
            return CAFE_EMOJI;
        }
        // 3) 그 외 (merchant 먼저, 그 다음 memo)
        for (var entry : RULES.entrySet()) {
            for (String kw : entry.getValue()) {
                if (contains(merchant, kw)) return entry.getKey();
            }
        }
        for (var entry : RULES.entrySet()) {
            for (String kw : entry.getValue()) {
                if (contains(memo, kw)) return entry.getKey();
            }
        }
        return DEFAULT_EMOJI;
    }

    private boolean contains(String text, String kw) {
        return text != null && kw != null && text.contains(kw);
    }

    private boolean containsAny(String text, List<String> kws) {
        if (text == null || text.isEmpty()) return false;
        for (String k : kws) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
