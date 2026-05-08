package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Notion API 에러 응답.
 * 예: { "object":"error", "status":404, "code":"object_not_found", "message":"..." }
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotionErrorResponse {
    private String object;
    private int status;
    private String code;
    private String message;
}
