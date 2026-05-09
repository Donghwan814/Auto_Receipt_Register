package com.example.receiptmemo.ledger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddReceiptsToPageResponse {
    private boolean success;
    private String pageId;
    private String title;
    private Integer totalAmount;
    private LocalDate date;
    private String memo;
    private List<ReceiptAnalysisResponse> receipts;
    private String warning;
    private List<String> warnings;
    /** resync 등에서 success=false 일 때 이유. */
    private String reason;
}
