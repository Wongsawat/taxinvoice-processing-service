package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence.outbox.OutboxEventEntity;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence.outbox.SpringDataOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying transaction propagation behaviour of SagaReplyPublisher.
 *
 * <p>publishFailure() and publishCompensated() are called from error-handling branches where the
 * outer transaction is either ROLLBACK_ONLY (DataIntegrityViolationException) or has already had
 * its Hibernate session invalidated by a DB-level error. They must commit their outbox entries in
 * an independent transaction so the orchestrator always receives a reply and the saga never hangs.
 *
 * <p>publishSuccess() must remain atomic with the domain state change, so it continues to use
 * MANDATORY propagation and commits together with the outer transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class SagaReplyPublisherTransactionTest {

    @Autowired
    private SagaReplyPublisher sagaReplyPublisher;

    @Autowired
    private SpringDataOutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        outboxRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // publishFailure — must survive an outer ROLLBACK_ONLY transaction
    // -------------------------------------------------------------------------

    @Test
    void publishFailure_commitsOutboxEntry_evenWhenOuterTransactionIsRollbackOnly() {
        String sagaId = UUID.randomUUID().toString();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Simulate what happens after DataIntegrityViolationException:
        // Spring/Hibernate marks the outer transaction ROLLBACK_ONLY before the
        // catch block calls publishFailure().
        try {
            txTemplate.execute(status -> {
                status.setRollbackOnly();
                sagaReplyPublisher.publishFailure(
                        sagaId, SagaStep.PROCESS_TAX_INVOICE, "corr-1", "duplicate key");
                return null;
            });
        } catch (UnexpectedRollbackException | TransactionSystemException ignored) {
            // expected — outer transaction was rolled back
        }

        // The failure reply outbox entry MUST be committed despite the outer rollback.
        List<OutboxEventEntity> entries = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(sagaId))
                .toList();

        assertFalse(entries.isEmpty(),
                "publishFailure() must commit its outbox entry in its own transaction " +
                "so the orchestrator receives a FAILURE reply even when the outer " +
                "transaction is rolled back");
    }

    @Test
    void publishFailure_outboxEntry_containsFailureStatus() {
        String sagaId = UUID.randomUUID().toString();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        try {
            txTemplate.execute(status -> {
                status.setRollbackOnly();
                sagaReplyPublisher.publishFailure(
                        sagaId, SagaStep.PROCESS_TAX_INVOICE, "corr-1", "some error");
                return null;
            });
        } catch (UnexpectedRollbackException | TransactionSystemException ignored) {
            // expected
        }

        List<OutboxEventEntity> entries = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(sagaId))
                .toList();

        assertFalse(entries.isEmpty(), "outbox entry must exist");
        assertTrue(entries.get(0).getPayload().contains("FAILURE"),
                "outbox payload must indicate FAILURE status");
    }

    // -------------------------------------------------------------------------
    // publishCompensated — must survive an outer ROLLBACK_ONLY transaction
    // (called from the catch block in compensate() when a DB error occurred)
    // -------------------------------------------------------------------------

    @Test
    void publishCompensated_commitsOutboxEntry_evenWhenOuterTransactionIsRollbackOnly() {
        String sagaId = UUID.randomUUID().toString();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        try {
            txTemplate.execute(status -> {
                status.setRollbackOnly();
                sagaReplyPublisher.publishCompensated(
                        sagaId, SagaStep.PROCESS_TAX_INVOICE, "corr-1");
                return null;
            });
        } catch (UnexpectedRollbackException | TransactionSystemException ignored) {
            // expected
        }

        List<OutboxEventEntity> entries = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(sagaId))
                .toList();

        assertFalse(entries.isEmpty(),
                "publishCompensated() must commit its outbox entry in its own transaction " +
                "so the orchestrator receives a COMPENSATED reply even when the outer " +
                "transaction is rolled back");
    }

    // -------------------------------------------------------------------------
    // publishSuccess — must remain ATOMIC with the outer business transaction
    // -------------------------------------------------------------------------

    @Test
    void publishSuccess_commitsOutboxEntry_togetherWithOuterTransaction() {
        String sagaId = UUID.randomUUID().toString();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // publishSuccess is called only on the happy path, within an active transaction.
        txTemplate.execute(status -> {
            sagaReplyPublisher.publishSuccess(
                    sagaId, SagaStep.PROCESS_TAX_INVOICE, "corr-1");
            return null;
        });

        List<OutboxEventEntity> entries = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(sagaId))
                .toList();

        assertFalse(entries.isEmpty(),
                "publishSuccess() outbox entry must be committed with the outer transaction");
    }

    @Test
    void publishSuccess_rollsBackOutboxEntry_whenOuterTransactionRollsBack() {
        String sagaId = UUID.randomUUID().toString();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // If the outer transaction is rolled back (e.g. exception after publishSuccess),
        // the success reply must also be rolled back — it must be atomic with domain state.
        try {
            txTemplate.execute(status -> {
                sagaReplyPublisher.publishSuccess(
                        sagaId, SagaStep.PROCESS_TAX_INVOICE, "corr-1");
                status.setRollbackOnly(); // something went wrong after the publish call
                return null;
            });
        } catch (UnexpectedRollbackException | TransactionSystemException ignored) {
            // expected
        }

        List<OutboxEventEntity> entries = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(sagaId))
                .toList();

        assertTrue(entries.isEmpty(),
                "publishSuccess() outbox entry must be rolled back together with the outer " +
                "transaction — a premature success reply must never be delivered");
    }
}
