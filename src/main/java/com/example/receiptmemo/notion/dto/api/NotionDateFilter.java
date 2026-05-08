package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Notion date 필터 옵션.
 * 셋 중 하나만 채우면 된다 (equals / on_or_after / on_or_before).
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionDateFilter {
    private String equals;
    private String on_or_after;
    private String on_or_before;
}
