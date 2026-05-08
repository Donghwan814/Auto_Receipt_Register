package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /v1/databases/{id}/query 요청 본문.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionDatabaseQueryRequest {
    private NotionCompoundFilter filter;
    private Integer page_size;
}
