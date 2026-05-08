package com.example.receiptmemo.notion.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Notion 웹훅 이벤트 처리 로그 (중복 방지용). */
@Entity
@Table(
        name = "webhook_event_log",
        indexes = {
                @Index(name = "idx_wel_event_id", columnList = "event_id", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 200, nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "page_id", length = 64)
    private String pageId;

    @Column(name = "comment_id", length = 64)
    private String commentId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (processedAt == null) processedAt = Instant.now();
    }
}
