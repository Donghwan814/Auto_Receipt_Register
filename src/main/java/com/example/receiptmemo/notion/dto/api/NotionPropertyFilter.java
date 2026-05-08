package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Notion 단일 property 필터.
 *  - property : 컬럼명
 *  - date / number / title 중 하나만 채움
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionPropertyFilter {
    private String property;
    private NotionDateFilter date;
    private NotionNumberFilter number;
    private NotionTitleFilter title;
}
