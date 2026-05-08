package com.example.receiptmemo.ledger.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Notion 페이지에 매칭된 영수증 1건. 같은 페이지에 여러 row 가 누적된다.
 */
@Entity
@Table(
        name = "processed_receipt",
        indexes = {
                @Index(name = "idx_pr_page", columnList = "notion_page_id"),
                @Index(name = "idx_pr_page_hash", columnList = "notion_page_id,file_hash", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notion_page_id", length = 64, nullable = false)
    private String notionPageId;

    /** SHA-256 hex (64자). 같은 페이지에 같은 해시는 unique. */
    @Column(name = "file_hash", length = 64, nullable = false)
    private String fileHash;

    @Column(length = 200)
    private String merchant;

    @Column(name = "receipt_date")
    private LocalDate receiptDate;

    private Integer amount;

    @Lob
    private String memo;

    @Lob
    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
