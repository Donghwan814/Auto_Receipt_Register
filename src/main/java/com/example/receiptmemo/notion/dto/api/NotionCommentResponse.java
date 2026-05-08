package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotionCommentResponse {
    private String id;
    private String object;
    private JsonNode parent;
    private String discussion_id;
    private String created_time;
    private String last_edited_time;
    private JsonNode created_by;
    private JsonNode rich_text;
    private List<NotionCommentAttachmentResponse> attachments;
}
