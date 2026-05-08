package com.example.receiptmemo.ledger;

import com.example.receiptmemo.ledger.service.EmojiTitleService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmojiTitleServiceTest {

    private final EmojiTitleService svc = new EmojiTitleService();

    @Test
    void 라멘_매칭() {
        assertThat(svc.resolveEmoji("마시타야")).isEqualTo("🍜");
        assertThat(svc.build("마시타야")).isEqualTo("🍜 마시타야");
    }

    @Test
    void 커피빈_카페() {
        assertThat(svc.resolveEmoji("커피빈 홍대점")).isEqualTo("☕");
    }

    @Test
    void 주차_우선() {
        assertThat(svc.resolveEmoji("강남 주차장")).isEqualTo("🅿️");
        // 카페 + 주차 동시 매칭 → 주차 우선
        assertThat(svc.resolveEmoji("스타벅스 주차장")).isEqualTo("🅿️");
    }

    @Test
    void 다이소_쇼핑() {
        assertThat(svc.resolveEmoji("다이소")).isEqualTo("🛍️");
    }

    @Test
    void 영화_여가() {
        assertThat(svc.resolveEmoji("CGV 강남")).isEqualTo("🎬");
    }

    @Test
    void GS25_편의점() {
        assertThat(svc.resolveEmoji("GS25 봉천점")).isEqualTo("🏪");
    }

    @Test
    void 기본값() {
        assertThat(svc.resolveEmoji("이상한가게")).isEqualTo("💳");
        assertThat(svc.resolveEmoji(null)).isEqualTo("💳");
    }

    @Test
    void merchant_없으면_가게_미상() {
        assertThat(svc.build(null)).isEqualTo("💳 (가게 미상)");
        assertThat(svc.build("")).isEqualTo("💳 (가게 미상)");
    }

    @Test
    void memo_fallback_매칭() {
        // merchant 가 매칭되지 않을 때 memo 로 fallback
        assertThat(svc.resolveEmoji("이상한가게", "라멘 + 교자")).isEqualTo("🍜");
    }
}
