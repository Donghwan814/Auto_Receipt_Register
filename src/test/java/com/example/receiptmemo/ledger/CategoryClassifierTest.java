package com.example.receiptmemo.ledger;

import com.example.receiptmemo.ledger.service.CategoryClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @Test
    void 마시타야_식비() {
        assertThat(classifier.classify("마시타야", "블랙라멘 + 시오라멘")).isEqualTo("식비");
    }

    @Test
    void 커피빈_카페() {
        assertThat(classifier.classify("커피빈 홍대점", "아이스 아메리카노")).isEqualTo("카페");
    }

    @Test
    void 주차장_교통비() {
        assertThat(classifier.classify("강남 주차장", null)).isEqualTo("교통비");
    }

    @Test
    void 롯데시네마_여가비() {
        assertThat(classifier.classify("롯데시네마", "영화티켓")).isEqualTo("여가비");
    }

    @Test
    void 다이소_쇼핑() {
        assertThat(classifier.classify("다이소", "문구")).isEqualTo("쇼핑");
    }

    @Test
    void GS25_초코우유_간식비() {
        assertThat(classifier.classify("GS25", "초코우유 + 과자")).isEqualTo("간식비");
    }

    @Test
    void 미상_기본_식비() {
        assertThat(classifier.classify(null, null)).isEqualTo("식비");
    }

    @Test
    void 카페_키워드는_merchant_에서_우선() {
        // merchant 에 카페 키워드 → 카페가 우선
        assertThat(classifier.classify("스타벅스 강남점", "샌드위치")).isEqualTo("카페");
    }

    @Test
    void 교통_키워드는_무조건_우선() {
        assertThat(classifier.classify("스타벅스 주차장", "음료")).isEqualTo("교통비");
    }
}
