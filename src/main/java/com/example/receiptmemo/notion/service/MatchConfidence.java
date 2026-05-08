package com.example.receiptmemo.notion.service;

/**
 * 후보 매칭 신뢰도.
 *  HIGH   : 날짜·금액 모두 일치 (가게명 보너스 가능)
 *  MEDIUM : 금액 일치 + 날짜 ±1일 이내
 *  LOW    : 날짜만 일치 또는 가게명만 매우 유사
 */
public enum MatchConfidence {
    HIGH, MEDIUM, LOW
}
