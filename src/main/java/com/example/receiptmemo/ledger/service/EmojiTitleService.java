package com.example.receiptmemo.ledger.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notion 항목의 title / icon 빌더.
 *
 * 변경 정책 (2026-05):
 *  - title 에는 이모지를 더 이상 붙이지 않는다. 이모지는 Notion page icon 으로 별도 설정.
 *  - title 형식: "{가게명} ({지역명})" — 지역명이 있을 때만 괄호 부분이 붙는다.
 *  - 지역명 추출 우선순위:
 *      1) 가게명 괄호 안 값 (예: "너구리베이글(팝업)" → 지역="팝업")
 *      2) 지점명 (예: "메가MGC 커피 영등포역점") — 그대로 두는 게 자연스러우면 그대로 둔다.
 *      3) 주소 기반 지역명 (호출자가 추출해 region 인자로 넘김)
 *      4) 추출 실패 시 괄호 생략
 *
 * 이모지 결정 규칙 (category 우선, 그 다음 keyword):
 *  - 카페      → ☕
 *  - 식비/식당 → 🍽️
 *  - 고기/구이 → 🐷
 *  - 라멘/일식 → 🍜
 *  - 베이글/빵/디저트 → 🥯
 *  - 영화/문화 → 🍿
 *  - 주차/교통 → 🅿️
 *  - 쇼핑      → 🛍️
 *  - 기본값    → 💳
 */
@Service
public class EmojiTitleService {

    public static final String DEFAULT_EMOJI = "💳";
    public static final String PARKING_EMOJI = "🅿️";
    public static final String CAFE_EMOJI = "☕";
    public static final String FOOD_EMOJI = "🍽️";
    public static final String MEAT_EMOJI = "🐷";
    public static final String RAMEN_EMOJI = "🍜";
    public static final String BAGEL_EMOJI = "🥯";
    public static final String MOVIE_EMOJI = "🍿";
    public static final String SHOPPING_EMOJI = "🛍️";

    private static final List<String> PARKING_KW = List.of("주차", "주차장", "파킹", "톨게이트", "택시", "지하철");
    private static final List<String> CAFE_KW = List.of(
            "카페", "커피", "스타벅스", "투썸", "커피빈", "메가커피", "메가mgc",
            "이디야", "빽다방", "컴포즈", "더벤티", "할리스"
    );
    private static final List<String> MEAT_KW = List.of("고기", "삼겹살", "갈비", "스테이크", "구이", "고기싸롱");
    private static final List<String> RAMEN_KW = List.of("라멘", "우동", "국수", "마시타야", "라면");
    private static final List<String> BAGEL_KW = List.of("베이글", "베이커리", "빵", "디저트", "도넛", "스콘");
    private static final List<String> MOVIE_KW = List.of("CGV", "롯데시네마", "메가박스", "영화", "씨네마", "팝콘");
    private static final List<String> SHOPPING_KW = List.of("다이소", "올리브영", "쇼핑", "마트", "백화점", "구매");
    private static final List<String> FOOD_KW = List.of("식당", "음식점", "분식", "한식", "중식", "양식", "치킨", "피자", "버거");

    private static final Map<String, List<String>> EMOJI_RULES = new LinkedHashMap<>();
    static {
        // 우선순위: 주차 → 카페 → 베이글/빵 → 라멘 → 고기 → 영화 → 쇼핑 → 식비
        EMOJI_RULES.put(PARKING_EMOJI, PARKING_KW);
        EMOJI_RULES.put(CAFE_EMOJI, CAFE_KW);
        EMOJI_RULES.put(BAGEL_EMOJI, BAGEL_KW);
        EMOJI_RULES.put(RAMEN_EMOJI, RAMEN_KW);
        EMOJI_RULES.put(MEAT_EMOJI, MEAT_KW);
        EMOJI_RULES.put(MOVIE_EMOJI, MOVIE_KW);
        EMOJI_RULES.put(SHOPPING_EMOJI, SHOPPING_KW);
        EMOJI_RULES.put(FOOD_EMOJI, FOOD_KW);
    }

    /** category → 기본 emoji 매핑 (카테고리가 명확할 때 우선). */
    private static final Map<String, String> CATEGORY_EMOJI = Map.of(
            "카페", CAFE_EMOJI,
            "교통비", PARKING_EMOJI,
            "쇼핑", SHOPPING_EMOJI,
            "여가비", MOVIE_EMOJI,
            "간식비", BAGEL_EMOJI,
            "식비", FOOD_EMOJI
    );

    private static final Pattern MERCHANT_PARENS = Pattern.compile("^(.+?)\\s*\\(\\s*([^)]+?)\\s*\\)\\s*$");

    /** 가게명에서 (지역/디스크립터) 부분을 분리한다. 괄호 없으면 region=null. */
    public MerchantParts splitMerchant(String merchant) {
        if (merchant == null || merchant.isBlank()) return new MerchantParts(null, null);
        Matcher m = MERCHANT_PARENS.matcher(merchant.trim());
        if (m.matches()) {
            return new MerchantParts(m.group(1).trim(), m.group(2).trim());
        }
        return new MerchantParts(merchant.trim(), null);
    }

    /** Notion title (이모지 제외) 생성. region 우선순위에 따라 "{base} ({region})". */
    public String buildTitle(String merchant, String regionFallback) {
        if (merchant == null || merchant.isBlank()) return "(가게 미상)";
        MerchantParts p = splitMerchant(merchant);
        String base = p.base != null && !p.base.isBlank() ? p.base : merchant.trim();
        String region = p.region != null && !p.region.isBlank() ? p.region
                : (regionFallback != null && !regionFallback.isBlank() ? regionFallback.trim() : null);
        if (region == null) return base;
        return base + " (" + region + ")";
    }

    /** category 가 있으면 우선, 없으면 merchant/memo 키워드로 판단. */
    public String resolveIcon(String merchant, String memo, String category) {
        // 1) 우선 주차/교통은 무조건 적용 (카테고리/키워드 무관)
        if (containsAny(merchant, PARKING_KW) || containsAny(memo, PARKING_KW)) return PARKING_EMOJI;
        // 2) 카테고리 매핑이 있으면 우선
        if (category != null) {
            String byCat = CATEGORY_EMOJI.get(category.trim());
            if (byCat != null && !byCat.equals(FOOD_EMOJI)) return byCat;
            // FOOD_EMOJI 는 키워드로 더 정밀하게 잡을 수 있을 때까지 보류
            if (byCat != null && containsAny(merchant, MEAT_KW)) return MEAT_EMOJI;
            if (byCat != null && containsAny(merchant, RAMEN_KW)) return RAMEN_EMOJI;
            if (byCat != null && containsAny(merchant, BAGEL_KW)) return BAGEL_EMOJI;
            if (byCat != null) return byCat;
        }
        // 3) 키워드 매칭
        for (var entry : EMOJI_RULES.entrySet()) {
            if (containsAny(merchant, entry.getValue())) return entry.getKey();
        }
        for (var entry : EMOJI_RULES.entrySet()) {
            if (containsAny(memo, entry.getValue())) return entry.getKey();
        }
        return DEFAULT_EMOJI;
    }

    public String resolveIcon(String merchant, String memo) {
        return resolveIcon(merchant, memo, null);
    }

    public String resolveIcon(String merchant) {
        return resolveIcon(merchant, null, null);
    }

    private boolean containsAny(String text, List<String> kws) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String k : kws) {
            if (lower.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    /** 가게명을 base + region 으로 분리한 결과. */
    public static class MerchantParts {
        public final String base;
        public final String region;
        public MerchantParts(String base, String region) {
            this.base = base;
            this.region = region;
        }
    }
}
