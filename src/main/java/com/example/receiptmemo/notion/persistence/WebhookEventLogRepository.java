package com.example.receiptmemo.notion.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventLogRepository extends JpaRepository<WebhookEventLog, Long> {
    boolean existsByEventId(String eventId);
}
