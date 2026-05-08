package com.example.receiptmemo.receipt.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 영수증의 한 줄(메뉴) 표현.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
public class ReceiptItem {

    /** 메뉴 이름 */
    private String name;

    /** 수량 (기본 1) */
    private int quantity;

    /** 단가 또는 합계가가 추출된 경우. 없으면 null. */
    private Integer price;

    public static ReceiptItem of(String name, int quantity, Integer price) {
        return ReceiptItem.builder().name(name).quantity(quantity).price(price).build();
    }
}
