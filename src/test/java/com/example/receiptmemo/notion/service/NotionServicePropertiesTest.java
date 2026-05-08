package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.global.config.NotionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotionServicePropertiesTest {

    private NotionService service(NotionConfig cfg) {
        return new NotionService(null, cfg, new ObjectMapper());
    }

    private NotionConfig configWithCategory() {
        NotionConfig c = new NotionConfig();
        c.getColumns().setName("항목");
        c.getColumns().setAmount("금액");
        c.getColumns().setDate("날짜");
        c.getColumns().setMemo("메모");
        c.getColumns().setCategory("카테고리");
        return c;
    }

    @Test
    void category_select_payload_가_올바른형태() {
        NotionService s = service(configWithCategory());
        Map<String, Object> props = s.buildExpenseProperties(
                "🍜 마시타야", 25000, LocalDate.of(2026, 5, 3),
                "식비", "블랙라멘", false);

        assertThat(props).containsKey("카테고리");
        Object cat = props.get("카테고리");
        assertThat(cat).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> catMap = (Map<String, Object>) cat;
        assertThat(catMap).containsKey("select");
        @SuppressWarnings("unchecked")
        Map<String, Object> sel = (Map<String, Object>) catMap.get("select");
        assertThat(sel).containsEntry("name", "식비");
    }

    @Test
    void 날짜는_date_컬럼_payload에_들어간다() {
        NotionService s = service(configWithCategory());
        Map<String, Object> props = s.buildExpenseProperties(
                "x", 1000, LocalDate.of(2026, 5, 3), null, "m", false);
        assertThat(props).containsKey("날짜");
        @SuppressWarnings("unchecked")
        Map<String, Object> dateMap = (Map<String, Object>) props.get("날짜");
        assertThat(dateMap).containsKey("date");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) dateMap.get("date");
        assertThat(d).containsEntry("start", "2026-05-03");
    }

    @Test
    void category_컬럼_미설정시_payload에_안들어감() {
        NotionConfig cfg = configWithCategory();
        cfg.getColumns().setCategory(null);
        NotionService s = service(cfg);
        Map<String, Object> props = s.buildExpenseProperties(
                "x", 1000, LocalDate.of(2026, 5, 3), "식비", "m", false);
        assertThat(props).doesNotContainKey("카테고리");
    }
}
