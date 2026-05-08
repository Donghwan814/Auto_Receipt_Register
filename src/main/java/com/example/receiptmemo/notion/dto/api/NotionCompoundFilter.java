package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Notion 복합 필터. and / or 둘 중 하나만 사용.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionCompoundFilter {
    private List<NotionPropertyFilter> and;
    private List<NotionPropertyFilter> or;
}
