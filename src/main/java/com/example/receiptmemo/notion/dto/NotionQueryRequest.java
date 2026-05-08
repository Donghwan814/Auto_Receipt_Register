package com.example.receiptmemo.notion.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 가계부 후보 조회 파라미터.
 */
@Getter
@Setter
@NoArgsConstructor
public class NotionQueryRequest {
    /** ISO 날짜 (yyyy-MM-dd) */
    private String date;
    private Integer amount;
    private String name;
}
