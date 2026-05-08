package com.example.receiptmemo.ledger;

import com.example.receiptmemo.ledger.service.EmojiTitleService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmojiTitleServiceTest {

    private final EmojiTitleService svc = new EmojiTitleService();

    // ---------- icon resolution ----------

    @Test
    void 카페_category면_커피_이모지() {
        assertThat(svc.resolveIcon("스타벅스 강남점", null, "카페")).isEqualTo("☕");
        assertThat(svc.resolveIcon("메가MGC 커피 영등포역점", null, "카페")).isEqualTo("☕");
    }

    @Test
    void 베이글은_빵_이모지() {
        assertThat(svc.resolveIcon("너구리베이글", null, null)).isEqualTo("🥯");
        assertThat(svc.resolveIcon("너구리베이글(팝업)", null, "식비")).isEqualTo("🥯");
    }

    @Test
    void 고기는_돼지_이모지() {
        assertThat(svc.resolveIcon("고기싸롱", null, null)).isEqualTo("🐷");
    }

    @Test
    void 라멘은_라멘_이모지() {
        assertThat(svc.resolveIcon("마시타야", null, null)).isEqualTo("🍜");
    }

    @Test
    void 주차_우선() {
        assertThat(svc.resolveIcon("스타벅스 주차장", null, "카페")).isEqualTo("🅿️");
    }

    @Test
    void 기본값() {
        assertThat(svc.resolveIcon("이상한가게", null, null)).isEqualTo("💳");
        assertThat(svc.resolveIcon(null, null, null)).isEqualTo("💳");
    }

    // ---------- title (이모지 미포함) ----------

    @Test
    void 가게명_괄호는_지역으로_분리() {
        EmojiTitleService.MerchantParts p = svc.splitMerchant("너구리베이글(팝업)");
        assertThat(p.base).isEqualTo("너구리베이글");
        assertThat(p.region).isEqualTo("팝업");
    }

    @Test
    void buildTitle_괄호_지역_포함() {
        assertThat(svc.buildTitle("너구리베이글(팝업)", null)).isEqualTo("너구리베이글 (팝업)");
    }

    @Test
    void buildTitle_지점명_있는_가게는_그대로() {
        assertThat(svc.buildTitle("메가MGC 커피 영등포역점", null))
                .isEqualTo("메가MGC 커피 영등포역점");
    }

    @Test
    void buildTitle_region_fallback_사용() {
        assertThat(svc.buildTitle("고기싸롱", "상계")).isEqualTo("고기싸롱 (상계)");
    }

    @Test
    void buildTitle_이모지_미포함() {
        assertThat(svc.buildTitle("마시타야", null)).doesNotContain("🍜").doesNotContain("💳");
    }

    @Test
    void buildTitle_가게_미상() {
        assertThat(svc.buildTitle(null, null)).isEqualTo("(가게 미상)");
        assertThat(svc.buildTitle("", null)).isEqualTo("(가게 미상)");
    }
}
