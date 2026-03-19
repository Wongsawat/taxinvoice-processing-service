package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence.outbox;

import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxCleanupSchedulerTest {

    private OutboxEventRepository repository;
    private SimpleMeterRegistry meterRegistry;
    private OutboxCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new OutboxCleanupScheduler(repository, meterRegistry);
    }

    @Test
    void cleanPublishedEvents_whenSuccessful_deletesEventsAndDoesNotIncrementFailureCounter() {
        when(repository.deletePublishedBefore(any(Instant.class))).thenReturn(42);

        scheduler.cleanPublishedEvents();

        verify(repository).deletePublishedBefore(any(Instant.class));
        assertEquals(0.0, failureCount(), "Failure counter must stay at zero on success");
    }

    @Test
    void cleanPublishedEvents_whenRepositoryThrows_incrementsFailureCounterAndDoesNotPropagate() {
        when(repository.deletePublishedBefore(any(Instant.class)))
            .thenThrow(new RuntimeException("DB connection lost"));

        // Must not propagate — scheduler must not crash the Spring scheduling thread
        scheduler.cleanPublishedEvents();

        assertEquals(1.0, failureCount(),
            "Failure counter must be incremented when cleanup throws");
    }

    @Test
    void cleanPublishedEvents_whenRepositoryThrowsRepeatedly_accumulatesFailureCount() {
        when(repository.deletePublishedBefore(any(Instant.class)))
            .thenThrow(new RuntimeException("DB connection lost"));

        scheduler.cleanPublishedEvents();
        scheduler.cleanPublishedEvents();
        scheduler.cleanPublishedEvents();

        assertEquals(3.0, failureCount(),
            "Failure counter must accumulate across repeated failures");
    }

    private double failureCount() {
        Counter counter = meterRegistry.find("outbox.cleanup.failure").counter();
        return counter == null ? 0.0 : counter.count();
    }
}
