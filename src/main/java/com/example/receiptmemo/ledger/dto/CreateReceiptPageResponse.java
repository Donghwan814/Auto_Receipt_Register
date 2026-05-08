package com.example.receiptmemo.ledger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateReceiptPageResponse {
    private boolean success;
    private String pageId;
    private String title;
    private Integer totalAmount;
    private LocalDate date;
    private String memo;
    private List<ReceiptAnalysisResponse> receipts;
    /** 가게가 섞여 있어 페이지를 만들지 못한 경우의 안내. */
    private String warning;
    /** 서로 다른 가게가 섞여 있어 거부됐는지. */
    private boolean rejected;
}
