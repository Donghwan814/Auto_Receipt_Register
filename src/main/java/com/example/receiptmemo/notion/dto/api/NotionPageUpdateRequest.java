package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * PATCH /v1/pages/{id} 요청 본문.
 * Notion 의 properties 구조가 컬럼명에 따라 동적이라 Map 으로 둔다.
 *
 * 메모(rich_text) 업데이트 헬퍼 제공.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionPageUpdateRequest {

    private Map<String, Object> properties;

    /** 단일 rich_text 컬럼 업데이트용 빌더. */
    public static NotionPageUpdateRequest forRichText(String columnName, String value) {
        Map<String, Object> richText = Map.of(
                "rich_text", List.of(
                        Map.of("text", Map.of("content", value == null ? "" : value))
                )
        );
        return NotionPageUpdateRequest.builder()
                .properties(Map.of(columnName, richText))
                .build();
    }
}
