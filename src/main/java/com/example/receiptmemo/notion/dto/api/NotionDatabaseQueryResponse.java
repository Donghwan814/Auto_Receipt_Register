package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * databases/{id}/query 응답.
 * results 안의 properties 는 컬럼이 동적이라 JsonNode 로 받는다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotionDatabaseQueryResponse {
    private List<Page> results;
    private boolean has_more;
    private String next_cursor;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        private String id;
        private JsonNode properties;
    }
}
