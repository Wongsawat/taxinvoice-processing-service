package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence.outbox;

import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job that deletes published outbox events older than the configured
 * retention window.
 *
 * <p>Without cleanup the {@code outbox_events} table grows without bound.
 * Each processed invoice writes at least two rows (saga reply + notification
 * event), so at 100k invoices/month the table accumulates 200k+ rows and
 * will eventually degrade Debezium CDC poll latency and {@code findPendingEvents}
 * query performance.
 *
 * <p>Configurable properties (with production-safe defaults):
 * <ul>
 *   <li>{@code app.outbox.cleanup.retention-days} — how many days to keep
 *       published events (default: 7)</li>
 *   <li>{@code app.outbox.cleanup.cron} — when to run (default: 02:00 daily)</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private final OutboxEventRepository outboxEventRepository;

    @Value("${app.outbox.cleanup.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${app.outbox.cleanup.cron:0 0 2 * * *}")
    @Transactional
    public void cleanPublishedEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deletePublishedBefore(cutoff);
        log.info("Outbox cleanup: deleted {} published events older than {} days", deleted, retentionDays);
    }
}
