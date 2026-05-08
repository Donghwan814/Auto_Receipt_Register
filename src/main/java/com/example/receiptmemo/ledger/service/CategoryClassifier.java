package com.example.receiptmemo.ledger.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가게명 + 메모(메뉴) 텍스트 기반 카테고리 분류기.
 *
 * 카테고리: 식비, 교통비, 간식비, 여가비, 쇼핑, 카페
 *
 * 우선순위
 *  1) merchant 우선
 *  2) merchant 로 정해지지 않으면 memo
 *  3) 그래도 정해지지 않으면 기본값 식비
 *  4) merchant 에 카페 키워드가 있으면 다른 카테고리보다 우선
 *  5) merchant/memo 에 교통/주차 키워드가 있으면 다른 카테고리보다 우선
 */
@Service
public class CategoryClassifier {

    public static final String FOOD = "식비";
    public static final String TRANSPORT = "교통비";
    public static final String SNACK = "간식비";
    public static final String LEISURE = "여가비";
    public static final String SHOPPING = "쇼핑";
    public static final String CAFE = "카페";

    private static final List<String> CAFE_KW = List.of(
            "커피", "카페", "스타벅스", "투썸", "커피빈", "메가커피", "빽다방",
            "이디야", "할리스", "컴포즈", "더벤티", "라떼", "아메리카노", "음료"
    );
    private static final List<String> FOOD_KW = List.of(
            "라멘", "마시타야", "고기", "삼겹살", "갈비", "스테이크", "김밥", "떡볶이",
            "치킨", "피자", "햄버거", "버거", "맥도날드", "롯데리아", "버거킹",
            "밥", "국밥", "식당", "음식점", "우동", "국수", "면", "마라탕"
    );
    private static final List<String> SNACK_KW = List.of(
            "편의점", "CU", "GS25", "세븐일레븐", "과자", "아이스크림", "디저트",
            "빵", "베이커리", "탕후루", "붕어빵", "간식", "초코우유"
    );
    private static final List<String> LEISURE_KW = List.of(
            "영화", "CGV", "롯데시네마", "메가박스", "보드게임", "게임카페", "PC방",
            "노래방", "카툰", "만화", "전시", "공연", "놀거리"
    );
    private static final List<String> TRANSPORT_KW = List.of(
            "주차", "주차장", "파킹", "택시", "버스", "지하철", "교통", "고속도로", "톨게이트"
    );
    private static final List<String> SHOPPING_KW = List.of(
            "다이소", "올리브영", "무신사", "네이버페이", "쿠팡", "쇼핑", "구매", "문구", "잡화"
    );

    /** category groups in priority order: 카페 / 교통 always overrides others. */
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put(CAFE, CAFE_KW);
        GROUPS.put(TRANSPORT, TRANSPORT_KW);
        GROUPS.put(FOOD, FOOD_KW);
        GROUPS.put(SNACK, SNACK_KW);
        GROUPS.put(LEISURE, LEISURE_KW);
        GROUPS.put(SHOPPING, SHOPPING_KW);
    }

    public String classify(String merchant, String memo) {
        String mLower = lower(merchant);
        String memoLower = lower(memo);

        // 5) merchant or memo 에 교통/주차 키워드 → 무조건 교통비
        if (containsAny(mLower, TRANSPORT_KW) || containsAny(memoLower, TRANSPORT_KW)) {
            return TRANSPORT;
        }
        // 4) merchant 에 카페 키워드 → 무조건 카페
        if (containsAny(mLower, CAFE_KW)) {
            return CAFE;
        }

        // 1) merchant 우선
        String byMerchant = matchFirst(mLower);
        if (byMerchant != null) return byMerchant;

        // 2) memo 보조
        String byMemo = matchFirst(memoLower);
        if (byMemo != null) return byMemo;

        // 3) 기본값
        return FOOD;
    }

    private String matchFirst(String text) {
        if (text == null || text.isBlank()) return null;
        for (Map.Entry<String, List<String>> e : GROUPS.entrySet()) {
            if (containsAny(text, e.getValue())) return e.getKey();
        }
        return null;
    }

    private boolean containsAny(String text, List<String> kws) {
        if (text == null || text.isEmpty()) return false;
        for (String k : kws) {
            if (text.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    private String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
