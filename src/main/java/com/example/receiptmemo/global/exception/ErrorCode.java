package com.example.receiptmemo.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 서비스 전반의 에러 코드 정의.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력입니다."),
    OCR_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "O001", "OCR 처리에 실패했습니다."),
    OCR_EMPTY_RESULT(HttpStatus.UNPROCESSABLE_ENTITY, "O002", "영수증 내용을 읽을 수 없습니다."),
    PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "P001", "영수증 텍스트 파싱에 실패했습니다."),
    NOTION_API_ERROR(HttpStatus.BAD_GATEWAY, "N001", "Notion API 호출에 실패했습니다."),
    NOTION_PAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "N002", "Notion 페이지를 찾지 못했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "S001", "서버 내부 오류입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
