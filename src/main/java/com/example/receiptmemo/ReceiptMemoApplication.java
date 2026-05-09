package com.example.receiptmemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 영수증 OCR → Notion 가계부 메모 자동 입력 서비스 진입점.
 */
@EnableAsync
@SpringBootApplication
public class ReceiptMemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReceiptMemoApplication.class, args);
    }
}
