package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.receipt.domain.ReceiptItem;
import com.example.receiptmemo.receipt.domain.ReceiptParseResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 영수증 OCR 텍스트 파싱.
 *
 * 처리 흐름
 *  1. 줄 단위 분할
 *  2. extractDate / extractMerchant
 *  3. "상품명" 마커 ~ "합계/부가세/받은금액/결제수단/거스름돈" 종료 마커 사이의 메뉴 구간 탐색
 *     - 메뉴 구간을 찾으면 그 구간 안의 줄만 메뉴 후보
 *     - 못 찾으면 전체 줄을 후보로 (휴리스틱)
 *  4. 각 후보 줄에 대해 shouldExcludeLine → parseLine
 *  5. " + " 로 메모 생성
 */
@Component
public class ReceiptParser {

    /** 메뉴/가게명 후보에서 제외할 키워드. compact(공백제거) 형태로도 매칭. */
    private static final List<String> EXCLUDE_KEYWORDS = List.of(
            "주문", "대기번호", "주문번호", "영수증", "매장명", "주소", "전화", "사업자",
            "대표자", "일시", "테이블", "상품명", "수량", "단가", "금액",
            "합계", "총합계", "부가세", "과세", "면세", "받을금액", "받은금액", "거스름돈",
            "결제수단", "결제내역", "카드", "카드번호", "승인", "승인번호", "KICC",
            "로직", "포스", "현금", "적용됨", "지출일부",
            "공급가", "할인", "잔액", "포인트", "결제금액"
    );

    /** "잔" 단위로 표기할 음료 키워드 */
    private static final List<String> DRINK_KEYWORDS = List.of(
            "아메리카노", "라떼", "라뗴", "커피", "에이드", "주스", "쥬스",
            "스무디", "티", "차", "음료", "콜라", "사이다", "맥주"
    );

    /** 주소로 의심되는 키워드. 줄 길이 ≥ 8자 + 키워드 ≥ 2개 매칭 시 주소로 판단. */
    private static final List<String> ADDRESS_KEYWORDS = List.of(
            "서울", "경기", "인천", "부산", "대구", "광주", "대전", "울산",
            "마포구", "강남구", "노원구", "상계", "홍대",
            "로", "길", "층", "번지"
    );

    /** "상품명" 마커 (메뉴 구간 시작) */
    private static final List<String> MENU_REGION_START = List.of("상품명");
    /** 메뉴 구간 종료 마커 */
    private static final List<String> MENU_REGION_END = List.of(
            "합계", "총합계", "부가세", "받은금액", "받을금액", "결제수단", "거스름돈", "결제금액"
    );

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
            "(?:합\\s*계|총\\s*합\\s*계|결제\\s*금액|받을\\s*금액|받은\\s*금액)[^0-9]*([0-9][0-9,\\.]*)"
    );

    private static final Pattern DATE_PATTERN_HYPHEN = Pattern.compile(
            "(20\\d{2})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})"
    );
    private static final Pattern DATE_PATTERN_KO = Pattern.compile(
            "(20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"
    );

    private static final Pattern PRICE_TAIL = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,})\\s*원?\\s*$");
    private static final Pattern QTY_INLINE = Pattern.compile("[xX*]\\s*([0-9]+)");
    private static final Pattern QTY_TAIL = Pattern.compile("\\s([0-9]{1,2})\\s*$");

    private static final Pattern DATE_LINE = Pattern.compile("^\\s*\\d{2,4}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}.*$");
    private static final Pattern DATE_LINE_KO = Pattern.compile("^\\s*20\\d{2}\\s*년\\s*\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일\\s*.*$");
    private static final Pattern TIME_LINE = Pattern.compile("^\\s*\\d{1,2}:\\d{2}(:\\d{2})?\\s*$");
    private static final Pattern NUMERIC_ONLY = Pattern.compile("^[\\s0-9,\\.원₩\\-]+$");
    /** "[02504346]" 같은 대괄호로 둘러싸인 숫자만의 줄 */
    private static final Pattern BRACKETED_DIGITS = Pattern.compile("^\\s*\\[[0-9\\s\\-,]+\\]\\s*$");

    /** 한 글자만 남으면 메뉴가 아닌 조각으로 본다 ("부", "가", "세" 등). */
    private static final int MIN_NAME_LENGTH = 2;

    public ReceiptParseResult parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return ReceiptParseResult.builder()
                    .rawText(rawText == null ? "" : rawText)
                    .items(List.of())
                    .totalAmount(null)
                    .memo("")
                    .confidence(ReceiptParseResult.Confidence.LOW)
                    .build();
        }

        String[] lines = rawText.split("\\r?\\n");
        Integer total = extractTotal(rawText);
        String date = extractDate(rawText);
        String merchant = extractMerchant(lines);

        // 메뉴 구간 추출. 못 찾으면 전체 줄을 후보로.
        List<String> candidateLines = extractMenuRegion(lines);
        if (candidateLines == null) {
            candidateLines = Arrays.stream(lines).filter(Objects::nonNull).toList();
        }

        LinkedHashMap<String, ReceiptItem> bucket = new LinkedHashMap<>();
        int rejected = 0;

        for (String raw : candidateLines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (shouldExcludeLine(line, merchant)) { rejected++; continue; }

            ReceiptItem item = parseLine(line);
            if (item == null) { rejected++; continue; }

            ReceiptItem existing = bucket.get(item.getName());
            if (existing == null) {
                bucket.put(item.getName(), item);
            } else {
                existing.setQuantity(existing.getQuantity() + item.getQuantity());
                if (existing.getPrice() == null) existing.setPrice(item.getPrice());
            }
        }

        List<ReceiptItem> items = new ArrayList<>(bucket.values());
        String memo = buildMemo(items);
        ReceiptParseResult.Confidence confidence = decideConfidence(items, total, rejected);

        return ReceiptParseResult.builder()
                .rawText(rawText)
                .items(items)
                .totalAmount(total)
                .memo(memo)
                .extractedDate(date)
                .extractedMerchant(merchant)
                .confidence(confidence)
                .build();
    }

    /**
     * "상품명"이 들어간 줄 직후부터 종료 마커 직전까지를 메뉴 구간으로 추출.
     * 시작 마커가 없으면 null 반환 → 호출자가 전체 줄 fallback.
     */
    List<String> extractMenuRegion(String[] lines) {
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (containsAny(compact(lines[i]), MENU_REGION_START)) { start = i; break; }
        }
        if (start < 0) return null;

        List<String> region = new ArrayList<>();
        for (int i = start + 1; i < lines.length; i++) {
            String c = compact(lines[i]);
            if (containsAny(c, MENU_REGION_END)) break;
            region.add(lines[i]);
        }
        return region.isEmpty() ? null : region;
    }

    String extractDate(String rawText) {
        Matcher m = DATE_PATTERN_HYPHEN.matcher(rawText);
        if (m.find()) return formatDate(m.group(1), m.group(2), m.group(3));
        m = DATE_PATTERN_KO.matcher(rawText);
        if (m.find()) return formatDate(m.group(1), m.group(2), m.group(3));
        return null;
    }

    private String formatDate(String y, String mo, String d) {
        try {
            int yy = Integer.parseInt(y);
            int mm = Integer.parseInt(mo);
            int dd = Integer.parseInt(d);
            if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return null;
            return String.format("%04d-%02d-%02d", yy, mm, dd);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 가게명 추정: 상단 8줄에서 제외 키워드/날짜/주소/괄호숫자/메뉴-수량 형태가 아닌
     * 첫 한글/영문 줄을 가게명으로 채택.
     */
    String extractMerchant(String[] lines) {
        int limit = Math.min(lines.length, 8);
        for (int i = 0; i < limit; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) continue;
            if (isExcluded(line)) continue;
            if (NUMERIC_ONLY.matcher(line).matches()) continue;
            if (BRACKETED_DIGITS.matcher(line).matches()) continue;
            if (isDateOnlyLine(line)) continue;
            if (TIME_LINE.matcher(line).matches()) continue;
            if (isAddressLine(line)) continue;
            // 메뉴처럼 끝에 수량/가격이 붙은 줄은 가게명 후보에서 제외
            if (PRICE_TAIL.matcher(line).find()) continue;
            if (QTY_TAIL.matcher(line).find()) continue;
            if (QTY_INLINE.matcher(line).find()) continue;
            if (line.matches(".*[가-힣A-Za-z].*")) {
                return cleanName(line);
            }
        }
        return null;
    }

    private ReceiptItem parseLine(String line) {
        String working = line;
        Integer price = null;
        int quantity = 1;

        Matcher pm = PRICE_TAIL.matcher(working);
        if (pm.find()) {
            price = parseAmount(pm.group(1));
            working = working.substring(0, pm.start()).trim();
        }

        Matcher qm = QTY_INLINE.matcher(working);
        if (qm.find()) {
            try { quantity = Integer.parseInt(qm.group(1)); } catch (NumberFormatException ignore) {}
            working = (working.substring(0, qm.start()) + working.substring(qm.end())).trim();
        } else {
            Matcher tm = QTY_TAIL.matcher(working);
            if (tm.find()) {
                try {
                    int q = Integer.parseInt(tm.group(1));
                    if (q > 0 && q < 100) {
                        quantity = q;
                        working = working.substring(0, tm.start()).trim();
                    }
                } catch (NumberFormatException ignore) {}
            }
        }

        String name = cleanName(working);
        if (name.length() < MIN_NAME_LENGTH) return null;
        if (!name.matches(".*[가-힣A-Za-z].*")) return null;

        return ReceiptItem.of(name, quantity, price);
    }

    /**
     * 메뉴명/가게명 정리.
     *  - 앞뒤의 공백, 콜론, 글머리 기호(- • · *) 제거
     *  - 앞뒤의 따옴표 제거 (ASCII " ', 유니코드 “ ” ‘ ’)
     *  - 단어 사이 다중 공백을 하나로 정규화
     *  - 메뉴명 중간의 괄호/+ 등은 그대로 유지
     */
    String cleanName(String s) {
        if (s == null) return "";
        String trimmed = s.replaceAll("^[\\-•·*\\s:\"'\\u201C\\u201D\\u2018\\u2019]+", "")
                .replaceAll("[\\-•·*\\s:\"'\\u201C\\u201D\\u2018\\u2019]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        // 앞뒤가 짝지어진 따옴표였다면 한 번 더 벗긴다 (예: " "마시타야" " 같은 이중 케이스)
        return trimmed
                .replaceAll("^[\"'\\u201C\\u201D\\u2018\\u2019]+", "")
                .replaceAll("[\"'\\u201C\\u201D\\u2018\\u2019]+$", "")
                .trim();
    }

    /**
     * 메뉴 후보에서 제외 여부.
     */
    boolean shouldExcludeLine(String line, String extractedMerchant) {
        if (line == null || line.isBlank()) return true;
        if (isExcluded(line)) return true;
        if (NUMERIC_ONLY.matcher(line).matches()) return true;
        if (BRACKETED_DIGITS.matcher(line).matches()) return true;
        if (isDateOnlyLine(line)) return true;
        if (TIME_LINE.matcher(line).matches()) return true;
        if (isMerchantLine(line, extractedMerchant)) return true;
        if (isAddressLine(line)) return true;
        return false;
    }

    boolean isMerchantLine(String line, String extractedMerchant) {
        if (extractedMerchant == null || extractedMerchant.isBlank()) return false;
        if (line == null) return false;
        return cleanName(line).equalsIgnoreCase(extractedMerchant.trim());
    }

    boolean isDateOnlyLine(String line) {
        if (line == null) return false;
        return DATE_LINE.matcher(line).matches() || DATE_LINE_KO.matcher(line).matches();
    }

    /**
     * 주소 줄 판정: 길이 ≥ 8 + 주소 키워드 ≥ 2개.
     * "길" 같은 단일 글자가 우연히 들어간 짧은 메뉴를 잘못 제외하지 않게 보수적으로.
     */
    boolean isAddressLine(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.length() < 8) return false;
        int hits = 0;
        for (String kw : ADDRESS_KEYWORDS) {
            if (containsAsToken(trimmed, kw) || trimmed.contains(kw)) hits++;
            if (hits >= 2) return true;
        }
        return false;
    }

    /** 단일 글자 키워드("로", "길", "층")는 단어 경계로 매칭해야 오탐을 줄임. */
    private boolean containsAsToken(String line, String kw) {
        if (kw.length() > 1) return line.contains(kw);
        return line.matches(".*(?:^|[\\s,()\\d])" + Pattern.quote(kw) + "(?:[\\s,()\\d]|$).*");
    }

    /** 제외 키워드 포함 여부. 공백 제거(compact) 버전으로도 매칭. */
    private boolean isExcluded(String line) {
        String c = compact(line);
        for (String kw : EXCLUDE_KEYWORDS) {
            if (line.contains(kw) || c.contains(kw)) return true;
        }
        return false;
    }

    private static String compact(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static boolean containsAny(String c, List<String> kws) {
        if (c == null) return false;
        for (String k : kws) if (c.contains(k)) return true;
        return false;
    }

    private Integer parseAmount(String token) {
        if (token == null) return null;
        try {
            return Integer.parseInt(token.replaceAll("[,\\.원₩\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer extractTotal(String rawText) {
        Matcher m = TOTAL_PATTERN.matcher(rawText);
        Integer last = null;
        while (m.find()) {
            Integer v = parseAmount(m.group(1));
            if (v != null) last = v;
        }
        return last;
    }

    public String buildMemo(List<ReceiptItem> items) {
        if (items == null || items.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(" + ");
        for (ReceiptItem item : items) sj.add(formatItem(item));
        return sj.toString();
    }

    private String formatItem(ReceiptItem item) {
        if (item.getQuantity() <= 1) return item.getName();
        String unit = isDrink(item.getName()) ? "잔" : "개";
        return item.getName() + " " + item.getQuantity() + unit;
    }

    private boolean isDrink(String name) {
        if (name == null) return false;
        for (String kw : DRINK_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return false;
    }

    private ReceiptParseResult.Confidence decideConfidence(List<ReceiptItem> items, Integer total, int rejected) {
        if (items.isEmpty()) return ReceiptParseResult.Confidence.LOW;
        if (total != null && items.size() >= 1 && rejected <= items.size() * 4)
            return ReceiptParseResult.Confidence.HIGH;
        if (total == null && items.size() >= 2)
            return ReceiptParseResult.Confidence.MEDIUM;
        if (items.size() == 1 && total == null)
            return ReceiptParseResult.Confidence.LOW;
        return ReceiptParseResult.Confidence.MEDIUM;
    }
}
