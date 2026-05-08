package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotionCommentListResponse {
    private String object;
    private List<NotionCommentResponse> results;
    private String next_cursor;
    private boolean has_more;
}
