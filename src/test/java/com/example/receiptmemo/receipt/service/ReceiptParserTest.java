package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.receipt.domain.ReceiptParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptParserTest {

    private final ReceiptParser parser = new ReceiptParser();

    @Test
    void 라멘_영수증_파싱() {
        String raw = """
                블랙라멘 1
                시오라멘 1
                공기밥 1
                합계 25,000원
                """;
        ReceiptParseResult r = parser.parse(raw);

        assertThat(r.getItems()).hasSize(3);
        assertThat(r.getItems().get(0).getName()).isEqualTo("블랙라멘");
        assertThat(r.getTotalAmount()).isEqualTo(25000);
        assertThat(r.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥");
        assertThat(r.getConfidence()).isEqualTo(ReceiptParseResult.Confidence.HIGH);
    }

    @Test
    void 음료_복수_수량은_잔으로_표기() {
        String raw = """
                치킨 떡볶이 콤보 1
                칠리치즈 핫도그 1
                아이스 아메리카노 2
                합계 44,000원
                """;
        ReceiptParseResult r = parser.parse(raw);

        assertThat(r.getMemo())
                .isEqualTo("치킨 떡볶이 콤보 + 칠리치즈 핫도그 + 아이스 아메리카노 2잔");
        assertThat(r.getTotalAmount()).isEqualTo(44000);
    }

    @Test
    void 일반음식_복수_수량은_개로_표기() {
        String raw = """
                떡볶이 2
                튀김 1
                합계 10000원
                """;
        ReceiptParseResult r = parser.parse(raw);
        assertThat(r.getMemo()).isEqualTo("떡볶이 2개 + 튀김");
    }

    @Test
    void 동일메뉴_수량_합산() {
        String raw = """
                아메리카노 1
                아메리카노 1
                합계 9000원
                """;
        ReceiptParseResult r = parser.parse(raw);
        assertThat(r.getItems()).hasSize(1);
        assertThat(r.getMemo()).isEqualTo("아메리카노 2잔");
    }

    @Test
    void 메뉴없는_입력은_LOW_신뢰도() {
        ReceiptParseResult r = parser.parse("합계 1000원");
        assertThat(r.getItems()).isEmpty();
        assertThat(r.getConfidence()).isEqualTo(ReceiptParseResult.Confidence.LOW);
    }

    @Test
    void 날짜_추출_하이픈() {
        var r = parser.parse("마시타야\n2026-05-03 19:30\n블랙라멘 1\n합계 13000원");
        assertThat(r.getExtractedDate()).isEqualTo("2026-05-03");
        assertThat(r.getExtractedMerchant()).isEqualTo("마시타야");
    }

    @Test
    void 날짜_추출_한국어() {
        var r = parser.parse("스시집\n2026년 5월 3일\n초밥 1\n합계 20000원");
        assertThat(r.getExtractedDate()).isEqualTo("2026-05-03");
    }

    @Test
    void 가게명과_한국어날짜_줄은_items에서_제외() {
        String raw = """
                마시타야
                2026년 5월 3일
                블랙라멘 1
                시오라멘 1
                공기밥 1
                합계 25,000원
                """;
        ReceiptParseResult r = parser.parse(raw);

        assertThat(r.getItems()).hasSize(3);
        assertThat(r.getItems().get(0).getName()).isEqualTo("블랙라멘");
        assertThat(r.getItems().get(1).getName()).isEqualTo("시오라멘");
        assertThat(r.getItems().get(2).getName()).isEqualTo("공기밥");
        assertThat(r.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공기밥");
        assertThat(r.getTotalAmount()).isEqualTo(25000);
        assertThat(r.getExtractedMerchant()).isEqualTo("마시타야");
        assertThat(r.getExtractedDate()).isEqualTo("2026-05-03");
    }

    @Test
    void isDateOnlyLine_각_포맷_매칭() {
        assertThat(parser.isDateOnlyLine("2026-05-03")).isTrue();
        assertThat(parser.isDateOnlyLine("2026.05.03")).isTrue();
        assertThat(parser.isDateOnlyLine("2026/05/03")).isTrue();
        assertThat(parser.isDateOnlyLine("2026년 5월 3일")).isTrue();
        assertThat(parser.isDateOnlyLine("2026년 05월 03일")).isTrue();
        assertThat(parser.isDateOnlyLine("블랙라멘 1")).isFalse();
    }

    @Test
    void isMerchantLine_정확히_일치할때만_true() {
        assertThat(parser.isMerchantLine("마시타야", "마시타야")).isTrue();
        assertThat(parser.isMerchantLine("  마시타야  ", "마시타야")).isTrue();
        assertThat(parser.isMerchantLine("마시타야 홍대점", "마시타야")).isFalse();
        assertThat(parser.isMerchantLine("블랙라멘", null)).isFalse();
    }

    @Test
    void 실제_KICC_영수증_노이즈_제거() {
        String raw = """
                마시타야
                [주문(대기)번호]
                2026년 5월 3일
                서울 마포구 와우산로29길 26
                상품명 수량
                블랙라멘 1
                시오라멘 1
                공깃밥 1
                부 가 세:
                합 계:
                받은금액:
                결제수단별 결제내역
                [02504346] KICC 로직
                """;
        ReceiptParseResult r = parser.parse(raw);

        assertThat(r.getExtractedMerchant()).isEqualTo("마시타야");
        assertThat(r.getExtractedDate()).isEqualTo("2026-05-03");
        assertThat(r.getItems()).hasSize(3);
        assertThat(r.getItems().get(0).getName()).isEqualTo("블랙라멘");
        assertThat(r.getItems().get(1).getName()).isEqualTo("시오라멘");
        assertThat(r.getItems().get(2).getName()).isEqualTo("공깃밥");
        assertThat(r.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공깃밥");
        // 합계 옆에 금액이 비어있는 케이스 → totalAmount 는 null 도 허용
    }

    @Test
    void 주소줄_제외_길이_8이상_키워드_2개이상() {
        assertThat(parser.isAddressLine("서울 마포구 와우산로29길 26")).isTrue();
        // 짧은 메뉴는 "길" 글자가 들어있어도 주소로 오인하면 안 됨
        assertThat(parser.isAddressLine("길거리음식")).isFalse();
        assertThat(parser.isAddressLine("로제떡볶이")).isFalse();
    }

    @Test
    void 메뉴구간_상품명_이후만_파싱() {
        String[] lines = ("쓸데없는 헤더\n상품명 수량\n블랙라멘 1\n시오라멘 1\n합계 25000원\n그 뒤 노이즈").split("\n");
        var region = parser.extractMenuRegion(lines);
        assertThat(region).containsExactly("블랙라멘 1", "시오라멘 1");
    }

    @Test
    void 부_가_세_같은_조각은_메뉴에서_제외() {
        String raw = """
                상품명 수량
                블랙라멘 1
                부 가 세:
                합 계:
                """;
        var r = parser.parse(raw);
        assertThat(r.getItems()).hasSize(1);
        assertThat(r.getItems().get(0).getName()).isEqualTo("블랙라멘");
    }

    @Test
    void merchant와_메뉴명_앞뒤_따옴표_제거() {
        String raw = """
                "마시타야"
                2026-05-03
                "블랙라멘" 1
                '시오라멘' 1
                “공깃밥” 1
                합계 25,000
                """;
        var r = parser.parse(raw);

        assertThat(r.getExtractedMerchant()).isEqualTo("마시타야");
        assertThat(r.getItems()).hasSize(3);
        assertThat(r.getItems().get(0).getName()).isEqualTo("블랙라멘");
        assertThat(r.getItems().get(1).getName()).isEqualTo("시오라멘");
        assertThat(r.getItems().get(2).getName()).isEqualTo("공깃밥");
        assertThat(r.getMemo()).isEqualTo("블랙라멘 + 시오라멘 + 공깃밥");
    }

    @Test
    void cleanName_은_따옴표를_제거하지만_괄호는_보존() {
        assertThat(parser.cleanName("\"마시타야\"")).isEqualTo("마시타야");
        assertThat(parser.cleanName("‘마시타야’")).isEqualTo("마시타야");
        assertThat(parser.cleanName("“마시타야”")).isEqualTo("마시타야");
        assertThat(parser.cleanName("'마시타야'")).isEqualTo("마시타야");
        assertThat(parser.cleanName("잡채(중)")).isEqualTo("잡채(중)");
        assertThat(parser.cleanName("아이스 아메리카노")).isEqualTo("아이스 아메리카노");
    }

    @Test
    void null_입력_안전() {
        ReceiptParseResult r = parser.parse(null);
        assertThat(r.getItems()).isEmpty();
        assertThat(r.getMemo()).isEmpty();
    }
}
