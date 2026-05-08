package com.example.receiptmemo.receipt.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * OCR 엔진 추상화 인터페이스. 구현체를 교체해서 Google Vision, Clova 등으로 전환할 수 있다.
 */
public interface OcrService {

    /**
     * 영수증 이미지에서 원시 텍스트를 추출한다.
     * @param image 영수증 이미지 파일
     * @return OCR 추출 텍스트
     */
    String extractText(MultipartFile image);
}
