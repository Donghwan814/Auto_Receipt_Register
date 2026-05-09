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
 * 핵심:
 *  - amount: "합계 → 받을금액 → 결제금액 → 카드결제 → 받은금액" 우선순위로 라벨 근처 1~3줄 안의 금액을 채택.
 *            사업자번호/전화번호/카드번호/승인번호/POS/BILL 등이 있는 줄은 후보에서 제외.
 *            1,000,000원 이상은 오인식 가능성이 높아 후보에서 제외.
 *  - merchant: 사업자번호(000-00-00000) 또는 TEL 줄 위쪽 1~3줄에서 우선 탐색.
 *              TIMES SQUARE / WARN / 1명 등은 후보에서 제외.
 */
@Component
public class ReceiptParser {

    private static final List<String> EXCLUDE_KEYWORDS = List.of(
            "주문", "대기번호", "주문번호", "영수증", "매장명", "주소", "전화", "사업자",
            "대표자", "일시", "테이블", "상품명", "수량", "단가", "금액",
            "합계", "총합계", "부가세", "과세", "면세", "받을금액", "받은금액", "거스름돈",
            "결제수단", "결제내역", "카드", "카드번호", "승인", "승인번호", "KICC",
            "로직", "포스", "현금", "적용됨", "지출일부",
            "공급가", "할인", "잔액", "포인트", "결제금액", "BILL", "POS", "바코드"
    );

    private static final List<String> DRINK_KEYWORDS = List.of(
            "아메리카노", "라떼", "라뗴", "커피", "에이드", "주스", "쥬스",
            "스무디", "티", "차", "음료", "콜라", "사이다", "맥주"
    );

    private static final List<String> ADDRESS_KEYWORDS = List.of(
            "서울", "경기", "인천", "부산", "대구", "광주", "대전", "울산",
            "마포구", "강남구", "노원구", "영등포구", "상계", "홍대",
            "특별시", "광역시", "로", "길", "층", "번지"
    );

    private static final List<String> MENU_REGION_START = List.of("상품명");
    private static final List<String> MENU_REGION_END = List.of(
            "합계", "총합계", "부가세", "받은금액", "받을금액", "결제수단", "거스름돈", "결제금액", "카드결제"
    );

    /** amount 라벨 우선순위. 위에서부터 매칭되는 첫 라벨이 amount 의 출처. */
    private static final List<String> AMOUNT_LABEL_PRIORITY = List.of(
            "합계", "받을금액", "결제금액", "카드결제", "받은금액"
    );

    /** amount 후보에서 반드시 제외할 라벨 (compact 비교). */
    private static final List<String> AMOUNT_EXCLUDE_LABELS = List.of(
            "부가세과세물품가액", "부가세", "공급가액", "할인금액", "할인"
    );

    private static final int AMOUNT_MAX = 1_000_000;

    private static final Pattern BIZ_NUM = Pattern.compile("\\d{3}-\\d{2}-\\d{5}");
    private static final Pattern PHONE = Pattern.compile("\\d{2,3}-\\d{3,4}-\\d{4}");
    private static final Pattern TEL_LABEL = Pattern.compile("(?i)TEL\\s*[:：]?");
    private static final Pattern CARD_NUM = Pattern.compile("\\d{4,6}\\*+\\d{2,4}\\*?|\\*{4,}");
    private static final Pattern APPROVAL_LABEL = Pattern.compile("(?i)승인|승인번호|승인 번호");

    private static final Pattern DATE_PATTERN_HYPHEN = Pattern.compile(
            "(20\\d{2})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})"
    );
    private static final Pattern DATE_PATTERN_KO = Pattern.compile(
            "(20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"
    );

    private static final Pattern PRICE_TAIL = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,})\\s*원?\\s*$");
    private static final Pattern QTY_INLINE = Pattern.compile("[xX*]\\s*([0-9]+)");
    private static final Pattern QTY_TAIL = Pattern.compile("\\s([0-9]{1,2})\\s*$");

    private static final Pattern AMOUNT_TOKEN = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,7})");

    private static final Pattern DATE_LINE = Pattern.compile("^\\s*\\d{2,4}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}.*$");
    private static final Pattern DATE_LINE_KO = Pattern.compile("^\\s*20\\d{2}\\s*년\\s*\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일\\s*.*$");
    private static final Pattern TIME_LINE = Pattern.compile("^\\s*\\d{1,2}:\\d{2}(:\\d{2})?\\s*$");
    private static final Pattern NUMERIC_ONLY = Pattern.compile("^[\\s0-9,\\.원₩\\-]+$");
    private static final Pattern BRACKETED_DIGITS = Pattern.compile("^\\s*\\[[0-9\\s\\-,]+\\]\\s*$");
    private static final Pattern PEOPLE_COUNT = Pattern.compile("^\\s*\\d+\\s*명\\s*$");
    private static final Pattern OCR_NOISE_MERCHANT = Pattern.compile("^(?i)(WA[A-Z]{2,3}|TIMES\\s*SQUARE)\\s*$");

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
        Integer total = extractTotal(lines);
        String date = extractDate(rawText);
        String merchant = extractMerchant(lines);

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
     * 가게명 추정.
     *  1순위: 사업자번호 또는 TEL 줄을 찾아 그 위 1~3줄에서 후보 탐색
     *  2순위: 상단 8줄에서 후보 탐색
     */
    String extractMerchant(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) continue;
            String l = lines[i];
            if (BIZ_NUM.matcher(l).find()
                    || PHONE.matcher(l).find()
                    || TEL_LABEL.matcher(l).find()) {
                for (int k = 1; k <= 3 && i - k >= 0; k++) {
                    String c = candidateMerchant(lines[i - k]);
                    if (c != null) return c;
                }
                break;
            }
        }
        int limit = Math.min(lines.length, 8);
        for (int i = 0; i < limit; i++) {
            String c = candidateMerchant(lines[i]);
            if (c != null) return c;
        }
        return null;
    }

    /** merchant 후보 한 줄 검사. 통과하면 cleanName 결과 반환. */
    private String candidateMerchant(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty()) return null;
        if (isExcluded(line)) return null;
        if (NUMERIC_ONLY.matcher(line).matches()) return null;
        if (BRACKETED_DIGITS.matcher(line).matches()) return null;
        if (PEOPLE_COUNT.matcher(line).matches()) return null;
        if (isDateOnlyLine(line)) return null;
        if (TIME_LINE.matcher(line).matches()) return null;
        if (isAddressLine(line)) return null;
        if (PRICE_TAIL.matcher(line).find()) return null;
        if (QTY_TAIL.matcher(line).find()) return null;
        if (QTY_INLINE.matcher(line).find()) return null;
        if (BIZ_NUM.matcher(line).find()) return null;
        if (PHONE.matcher(line).find()) return null;
        if (TEL_LABEL.matcher(line).find()) return null;
        if (OCR_NOISE_MERCHANT.matcher(line.trim()).matches()) return null;
        if (!line.matches(".*[가-힣A-Za-z].*")) return null;
        return cleanName(line);
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

    String cleanName(String s) {
        if (s == null) return "";
        String trimmed = s.replaceAll("^[\\-•·*\\s:\"'\\u201C\\u201D\\u2018\\u2019]+", "")
                .replaceAll("[\\-•·*\\s:\"'\\u201C\\u201D\\u2018\\u2019]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return trimmed
                .replaceAll("^[\"'\\u201C\\u201D\\u2018\\u2019]+", "")
                .replaceAll("[\"'\\u201C\\u201D\\u2018\\u2019]+$", "")
                .trim();
    }

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

    private boolean containsAsToken(String line, String kw) {
        if (kw.length() > 1) return line.contains(kw);
        return line.matches(".*(?:^|[\\s,()\\d])" + Pattern.quote(kw) + "(?:[\\s,()\\d]|$).*");
    }

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

    /**
     * amount 추출. 우선순위 라벨을 위에서부터 시도해 가장 먼저 매칭되는 값을 반환.
     * 사업자번호/전화/카드/승인번호/POS/BILL 줄은 후보에서 제외하고,
     * 1,000,000 이상의 값은 오인식 가능성이 높아 제외한다.
     */
    Integer extractTotal(String[] lines) {
        for (String label : AMOUNT_LABEL_PRIORITY) {
            Integer found = scanByLabel(lines, label);
            if (found != null) return found;
<<<<<<< ours
        }
        return null;
    }

    /** test 호환을 위해 String도 받는 오버로드. */
    Integer extractTotal(String rawText) {
        if (rawText == null) return null;
        return extractTotal(rawText.split("\\r?\\n"));
    }

    /**
     * window(1~3줄) 단위로 라벨을 탐색.
     * OCR이 "합" / "계:" / "5,500" 처럼 라벨을 여러 줄로 쪼개도 인식할 수 있도록
     * window 안의 텍스트를 정규화(공백/콜론/탭 제거)하여 라벨 키 포함 여부를 판단한다.
     *
     * 금액 추출 순서:
     *  1) 라벨 키 위치 다음의 tail 안에 amount token 이 있으면 사용
     *     (단, tail 에서 더 앞쪽에 다른 priority 라벨 또는 excluded 라벨이 보이면 그 앞까지만)
     *  2) window 다음 1~4줄 사이에서 amount 후보 탐색
     *     (부가세/카드/POS 등 noise 라인이나 다른 priority 라벨을 만나면 즉시 중단)
     */
    private Integer scanByLabel(String[] lines, String label) {
        String labelKey = normalizeLabelCandidate(label);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) continue;
            int maxWin = Math.min(3, lines.length - i);
            for (int win = 1; win <= maxWin; win++) {
                String joined = compactWindow(lines, i, win);
                int idx = joined.indexOf(labelKey);
                if (idx < 0) continue;

                String tail = joined.substring(idx + labelKey.length());
                Integer inline = firstAmountInTail(tail, label);
                if (inline != null && inline <= AMOUNT_MAX) return inline;

                for (int k = 0; k < 4 && i + win + k < lines.length; k++) {
                    String next = lines[i + win + k];
                    if (next == null || next.trim().isEmpty()) continue;
                    if (isAmountNoiseLine(next)) break;
                    if (isAnotherLabel(next, label)) break;
                    Integer val = firstAmountToken(next);
                    if (val != null && val <= AMOUNT_MAX) return val;
                }
                // 라벨은 찾았지만 amount 가 없거나 1M 초과였다면, 더 큰 window 로 재시도하지 않고
                // 다음 i 로 이동한다 (같은 i 의 더 큰 window 는 라벨 위치만 같고 결론도 동일).
                break;
            }
        }
        return null;
    }

    /** OCR 라벨 정규화: 공백/콜론/탭 제거. "합 계:" → "합계", "합\n계:" 도 합쳐서 정규화하면 "합계". */
    static String normalizeLabelCandidate(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s:：]+", "");
    }

    /** lines[start..start+size-1] 를 이어붙여 정규화한 문자열. */
    private String compactWindow(String[] lines, int start, int size) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < size && start + k < lines.length; k++) {
            if (lines[start + k] != null) sb.append(lines[start + k]);
        }
        return normalizeLabelCandidate(sb.toString());
    }

    /**
     * tail 의 첫 amount token 추출. 단, tail 안에 더 앞쪽에 excluded 라벨이나
     * 다른 priority 라벨이 있으면 그 앞부분까지만 본다 (받을금액/할인금액 같은 다른 값을 흡수하지 않게).
     */
    private Integer firstAmountInTail(String tail, String currentLabel) {
        if (tail == null || tail.isEmpty()) return null;
        String currentKey = normalizeLabelCandidate(currentLabel);
        int stop = tail.length();
        for (String ex : AMOUNT_EXCLUDE_LABELS) {
            int p = tail.indexOf(ex);
            if (p >= 0 && p < stop) stop = p;
        }
        for (String l : AMOUNT_LABEL_PRIORITY) {
            String lc = normalizeLabelCandidate(l);
            if (lc.equals(currentKey)) continue;
            int p = tail.indexOf(lc);
            if (p >= 0 && p < stop) stop = p;
        }
        String safe = tail.substring(0, stop);
        Matcher m = AMOUNT_TOKEN.matcher(safe);
        if (m.find()) return parseAmount(m.group(1));
        return null;
    }

    /** 사업자번호/전화/카드/승인/POS/BILL 줄 → 금액 후보로 부적합. */
    private boolean isAmountNoiseLine(String line) {
        if (BIZ_NUM.matcher(line).find()) return true;
        if (PHONE.matcher(line).find()) return true;
        if (CARD_NUM.matcher(line).find()) return true;
        if (APPROVAL_LABEL.matcher(line).find()) return true;
        String c = compact(line);
        if (c.contains("POS")) return true;
        if (c.contains("BILL")) return true;
        if (c.contains("바코드")) return true;
        if (c.contains("주문번호")) return true;
        for (String ex : AMOUNT_EXCLUDE_LABELS) {
            if (c.contains(ex)) return true;
        }
=======
        }
        return null;
    }

    /** test 호환을 위해 String도 받는 오버로드. */
    Integer extractTotal(String rawText) {
        if (rawText == null) return null;
        return extractTotal(rawText.split("\\r?\\n"));
    }

    private Integer scanByLabel(String[] lines, String label) {
        String labelCompact = compact(label);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) continue;
            String c = compact(lines[i]);
            if (!c.contains(labelCompact)) continue;
            // 라벨 줄이지만 사실상 다른 라벨에 의해 가려지는 경우 방지
            if (matchesExcludedAmountLabel(c, labelCompact)) continue;

            // 1) 같은 줄, 라벨 뒤에서 숫자 추출
            Integer same = amountAfterLabel(lines[i], label);
            if (same != null && same <= AMOUNT_MAX) return same;

            // 2) 다음 1~3줄에서 숫자 추출
            for (int k = 1; k <= 3 && i + k < lines.length; k++) {
                String next = lines[i + k];
                if (next == null || next.trim().isEmpty()) continue;
                if (isAmountNoiseLine(next)) continue;
                if (isAnotherLabel(next, label)) break; // 다른 라벨 만나면 중단
                Integer val = firstAmountToken(next);
                if (val != null && val <= AMOUNT_MAX) return val;
            }
        }
        return null;
    }

    private boolean matchesExcludedAmountLabel(String compactLine, String wantedCompact) {
        for (String ex : AMOUNT_EXCLUDE_LABELS) {
            if (compactLine.contains(ex) && !ex.contains(wantedCompact) && !wantedCompact.contains(ex)) {
                return true;
            }
        }
        return false;
    }

    /** 사업자번호/전화/카드/승인/POS/BILL 줄 → 금액 후보로 부적합. */
    private boolean isAmountNoiseLine(String line) {
        if (BIZ_NUM.matcher(line).find()) return true;
        if (PHONE.matcher(line).find()) return true;
        if (CARD_NUM.matcher(line).find()) return true;
        if (APPROVAL_LABEL.matcher(line).find()) return true;
        String c = compact(line);
        if (c.contains("POS")) return true;
        if (c.contains("BILL")) return true;
        if (c.contains("바코드")) return true;
        if (c.contains("주문번호")) return true;
        for (String ex : AMOUNT_EXCLUDE_LABELS) {
            if (c.contains(ex)) return true;
        }
>>>>>>> theirs
        return false;
    }

    private boolean isAnotherLabel(String line, String currentLabel) {
        String c = compact(line);
        String cur = compact(currentLabel);
        for (String l : AMOUNT_LABEL_PRIORITY) {
            String lc = compact(l);
            if (lc.equals(cur)) continue;
            if (c.contains(lc)) return true;
        }
        return false;
    }

<<<<<<< ours
=======
    private Integer amountAfterLabel(String line, String label) {
        String labelPattern = label.chars()
                .mapToObj(ch -> Pattern.quote(String.valueOf((char) ch)))
                .reduce((a, b) -> a + "\\s*" + b)
                .orElse("");
        Pattern p = Pattern.compile(labelPattern + "[^0-9]*" + AMOUNT_TOKEN.pattern());
        Matcher m = p.matcher(line);
        if (m.find()) {
            return parseAmount(m.group(1));
        }
        return null;
    }

>>>>>>> theirs
    private Integer firstAmountToken(String line) {
        Matcher m = AMOUNT_TOKEN.matcher(line);
        if (m.find()) {
            String tok = m.group(1);
            // 콤마 없는 단순 짧은 숫자(2~3자리)는 가격이라기엔 약하지만, 받아둔다
            return parseAmount(tok);
        }
        return null;
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
