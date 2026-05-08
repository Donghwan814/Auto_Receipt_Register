package com.example.receiptmemo.notion.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * /api/notion/pages/{pageId}/memo 의 요청 본문.
 */
@Getter
@Setter
@NoArgsConstructor
public class NotionMemoUpdateRequest {

    @NotNull(message = "memo 는 필수입니다.")
    private String memo;
}
