package com.example.receiptmemo.ledger.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessedReceiptRepository extends JpaRepository<ProcessedReceipt, Long> {

    boolean existsByNotionPageIdAndFileHash(String notionPageId, String fileHash);

    List<ProcessedReceipt> findByNotionPageId(String notionPageId);

    List<ProcessedReceipt> findByNotionPageIdOrderByCreatedAtAsc(String notionPageId);

    /** resync 시 페이지의 기존 영수증 row 를 모두 삭제. */
    long deleteByNotionPageId(String notionPageId);
}
