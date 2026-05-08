package com.example.receiptmemo.receipt.service;

import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

/**
 * OCR 구현체에서 공통으로 쓰는 유틸.
 */
final class OcrSupport {
    private OcrSupport() {}

    /** MultipartFile → base64 문자열. */
    static String toBase64(MultipartFile file) {
        try {
            return Base64.getEncoder().encodeToString(file.getBytes());
        } catch (IOException e) {
            throw new CustomException(ErrorCode.OCR_FAILED, "이미지 읽기 실패: " + e.getMessage());
        }
    }

    /** 추출 텍스트가 비어 있으면 OCR_EMPTY_RESULT 예외. */
    static String requireNonEmpty(String text) {
        if (text == null || text.isBlank()) {
            throw new CustomException(ErrorCode.OCR_EMPTY_RESULT);
        }
        return text;
    }

    /** 파일 확장자에서 image format 추출 (jpg/png/...). 없으면 jpg. */
    static String detectFormat(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return "jpg";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "jpg";
        String ext = name.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "jpeg" -> "jpg";
            case "png", "jpg", "pdf", "tiff", "bmp" -> ext;
            default -> "jpg";
        };
    }
}
