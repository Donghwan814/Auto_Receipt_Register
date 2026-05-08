package com.example.receiptmemo.ledger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReceiptPreviewResponse {
    private boolean success;
    private List<ReceiptAnalysisResponse> receipts;
}
