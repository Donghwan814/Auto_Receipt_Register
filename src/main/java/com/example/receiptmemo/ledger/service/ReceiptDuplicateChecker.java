package com.example.receiptmemo.ledger.service;

import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import com.example.receiptmemo.ledger.persistence.ProcessedReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 영수증 파일 SHA-256 해시 계산 + (notionPageId + fileHash) 중복 여부 판정.
 */
@Component
@RequiredArgsConstructor
public class ReceiptDuplicateChecker {

    private final ProcessedReceiptRepository repository;

    /** MultipartFile 의 SHA-256 hex 문자열을 계산. */
    public String hashOf(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            return sha256Hex(bytes);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "파일 읽기 실패: " + e.getMessage());
        }
    }

    public boolean isDuplicate(String notionPageId, String fileHash) {
        if (notionPageId == null || fileHash == null) return false;
        return repository.existsByNotionPageIdAndFileHash(notionPageId, fileHash);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "SHA-256 미지원");
        }
    }
}
