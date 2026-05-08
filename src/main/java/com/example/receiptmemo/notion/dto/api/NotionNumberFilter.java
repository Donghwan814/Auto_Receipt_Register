package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Notion number 필터 옵션.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionNumberFilter {
    private Integer equals;
    private Integer greater_than_or_equal_to;
    private Integer less_than_or_equal_to;
}
