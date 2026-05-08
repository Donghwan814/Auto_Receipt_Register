package com.example.receiptmemo.receipt.dto;

import com.example.receiptmemo.receipt.domain.ReceiptItem;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 응답에 사용되는 메뉴 항목 DTO.
 */
@Getter
@AllArgsConstructor
public class ReceiptItemResponse {

    private String name;
    private int quantity;
    private Integer price;

    public static ReceiptItemResponse from(ReceiptItem item) {
        return new ReceiptItemResponse(item.getName(), item.getQuantity(), item.getPrice());
    }
}
