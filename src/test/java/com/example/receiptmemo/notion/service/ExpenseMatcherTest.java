package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseMatcherTest {

    private final ExpenseMatcher matcher = new ExpenseMatcher();

    private NotionPageCandidateResponse page(String id, String title, Integer amount, String date) {
        return NotionPageCandidateResponse.builder()
                .pageId(id).title(title).amount(amount).date(date).build();
    }

    @Test
    void 날짜와_금액이_모두_일치하면_HIGH() {
        var raw = List.of(page("p1", "🍜 마시타야 (홍대)", 25000, "2026-05-03"));
        var scored = matcher.score(raw, "2026-05-03", 25000, null);

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).getConfidence()).isEqualTo("HIGH");
        assertThat(matcher.isAutoUpdatable(scored)).isTrue();
    }

    @Test
    void 금액일치_날짜_하루차이는_MEDIUM() {
        var raw = List.of(page("p1", "어떤가게", 25000, "2026-05-04"));
        var scored = matcher.score(raw, "2026-05-03", 25000, null);

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).getConfidence()).isEqualTo("MEDIUM");
        assertThat(matcher.isAutoUpdatable(scored)).isFalse();
    }

    @Test
    void 날짜만_일치하고_금액다르면_LOW() {
        var raw = List.of(page("p1", "어떤가게", 30000, "2026-05-03"));
        var scored = matcher.score(raw, "2026-05-03", 25000, null);

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).getConfidence()).isEqualTo("LOW");
    }

    @Test
    void 가게명_유사하면_신뢰도_상승() {
        // 금액 일치 + 날짜 1일차이(MEDIUM) + 가게명 일치 → HIGH 로 상승
        var raw = List.of(page("p1", "🍜 마시타야 (홍대)", 25000, "2026-05-04"));
        var scored = matcher.score(raw, "2026-05-03", 25000, "마시타야");

        assertThat(scored.get(0).getConfidence()).isEqualTo("HIGH");
    }

    @Test
    void 매칭불가는_제외() {
        var raw = List.of(page("p1", "다른가게", 99999, "2025-01-01"));
        var scored = matcher.score(raw, "2026-05-03", 25000, "마시타야");
        assertThat(scored).isEmpty();
    }

    @Test
    void 후보가_여러개면_자동업데이트_불가() {
        var raw = List.of(
                page("p1", "마시타야", 25000, "2026-05-03"),
                page("p2", "마시타야 강남", 25000, "2026-05-03")
        );
        var scored = matcher.score(raw, "2026-05-03", 25000, null);
        assertThat(scored).hasSize(2);
        assertThat(matcher.isAutoUpdatable(scored)).isFalse();
    }
}
