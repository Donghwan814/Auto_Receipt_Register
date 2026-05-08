package com.example.receiptmemo.notion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Notion 가계부 후보 항목 응답 DTO.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionPageCandidateResponse {
    private String pageId;
    /** 가계부 "이름"(title) */
    private String title;
    private Integer amount;
    private String date;
    /** HIGH / MEDIUM / LOW. 매칭 단계에서 채워짐. */
    private String confidence;
}
