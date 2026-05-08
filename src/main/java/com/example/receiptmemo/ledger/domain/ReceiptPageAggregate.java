package com.example.receiptmemo.ledger.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 한 Notion 페이지에 누적된 영수증들의 집계 결과.
 *  - title  : 이모지 + 가게명
 *  - amount : 금액 합계 (중복 제외)
 *  - date   : 가장 이른 날짜 또는 fallback (요청 date / 오늘)
 *  - memo   : 모든 메뉴를 " + " 로 합친 문자열
 *  - warning: 서로 다른 가게가 섞이는 등 주의 사항
 */
@Getter
@Builder
public class ReceiptPageAggregate {
    private String title;
    private String merchant;
    private int amount;
    private LocalDate date;
    private String memo;
    private String warning;
    private String category;
}
