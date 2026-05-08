package com.example.receiptmemo.global.exception;

import lombok.Getter;

/**
 * 도메인 예외. ErrorCode 와 함께 사용한다.
 */
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public CustomException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + " : " + detail);
        this.errorCode = errorCode;
    }
}
