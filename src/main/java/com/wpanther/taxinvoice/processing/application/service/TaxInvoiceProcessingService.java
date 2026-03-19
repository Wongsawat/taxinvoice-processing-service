package com.wpanther.taxinvoice.processing.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for tax invoice processing.
 * Implements inbound ports for processing and compensation.
 * Uses outbound ports via TaxInvoiceEventPublishingPort for event publishing and SagaReplyPort for saga replies.
 */
@Service
@Slf4j
public class TaxInvoiceProcessingService implements ProcessTaxInvoiceUseCase, CompensateTaxInvoiceUseCase {

    private final ProcessedTaxInvoiceRepository invoiceRepository;
    private final TaxInvoiceParserPort parserService;
    private final TaxInvoiceEventPublishingPort eventPublisher;
    private final SagaReplyPort sagaReplyPort;
    private final MeterRegistry meterRegistry;

    // Fresh-transaction executor for replying after a ROLLBACK_ONLY outer transaction
    private final TransactionTemplate requiresNewTemplate;

    // Metrics - initialized once in constructor
    private final Counter processSuccessCounter;
    private final Counter processFailureCounter;
    private final Counter processIdempotentCounter;
    private final Counter processConcurrentDuplicateCounter;
    private final Counter compensateSuccessCounter;
    private final Counter compensateIdempotentCounter;
    private final Counter compensateFailureCounter;
    private final Timer processingTimer;

    public TaxInvoiceProcessingService(
            ProcessedTaxInvoiceRepository invoiceRepository,
            TaxInvoiceParserPort parserService,
            TaxInvoiceEventPublishingPort eventPublisher,
            SagaReplyPort sagaReplyPort,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.parserService = parserService;
        this.eventPublisher = eventPublisher;
        this.sagaReplyPort = sagaReplyPort;
        this.meterRegistry = meterRegistry;

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;

        // Initialize metrics once
        this.processSuccessCounter = Counter.builder("taxinvoice.processing.success")
            .description("Number of successfully processed tax invoices")
            .register(meterRegistry);
        this.processFailureCounter = Counter.builder("taxinvoice.processing.failure")
            .description("Number of failed tax invoice processing attempts")
            .register(meterRegistry);
        this.processIdempotentCounter = Counter.builder("taxinvoice.processing.idempotent")
            .description("Number of duplicate processing requests handled idempotently")
            .register(meterRegistry);
        this.processConcurrentDuplicateCounter = Counter.builder("taxinvoice.processing.concurrent_duplicate")
            .description("Number of DuplicateKeyExceptions resolved as concurrent duplicate inserts on source_invoice_id")
            .register(meterRegistry);
        this.compensateSuccessCounter = Counter.builder("taxinvoice.compensation.success")
            .description("Number of successful compensations")
            .register(meterRegistry);
        this.compensateIdempotentCounter = Counter.builder("taxinvoice.compensation.idempotent")
            .description("Number of duplicate compensation commands received for an already-deleted invoice")
            .register(meterRegistry);
        this.compensateFailureCounter = Counter.builder("taxinvoice.compensation.failure")
            .description("Number of failed compensation attempts")
            .register(meterRegistry);
        this.processingTimer = Timer.builder("taxinvoice.processing.duration")
            .description("Time taken to process tax invoices")
            .register(meterRegistry);
    }

    /**
     * Process tax invoice as part of a saga command.
     * Parses XML, validates, calculates totals, saves to DB, publishes notification event.
     */
    @Override
    @Transactional
    public void process(String documentId, String xmlContent,
                         String sagaId, SagaStep sagaStep, String correlationId) throws TaxInvoiceProcessingException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            processInvoiceForSagaInternal(documentId, xmlContent, sagaId, sagaStep, correlationId);
        } catch (TaxInvoiceParserPort.TaxInvoiceParsingException e) {
            processFailureCounter.increment();
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Parse error: " + e.toString());
            throw new TaxInvoiceProcessingException("Failed to parse tax invoice: " + e.toString(), e);
        } catch (DuplicateKeyException e) {
            // Race-condition duplicate insert on source_invoice_id unique constraint.
            // Spring translates both PostgreSQL error 23505 and H2 unique index violations
            // to DuplicateKeyException, so this catch is DB-dialect-agnostic.
            // The outer transaction is ROLLBACK_ONLY; re-check in a fresh REQUIRES_NEW
            // transaction so we can reply SUCCESS if a concurrent thread already committed
            // the document — preventing the orchestrator from compensating committed work.
            log.warn("DuplicateKeyException for document {}, saga {} — re-checking for concurrent insert",
                    documentId, sagaId);
            requiresNewTemplate.execute(txStatus -> {
                Optional<ProcessedTaxInvoice> existing = invoiceRepository.findBySourceInvoiceId(documentId);
                if (existing.isPresent()) {
                    // Concurrent thread committed the same document first; treat as idempotent success.
                    log.warn("Race condition resolved: document {} already committed by concurrent thread — replying SUCCESS",
                            documentId);
                    processConcurrentDuplicateCounter.increment();
                    sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
                } else {
                    // Duplicate-key signal but no record found — unexpected state
                    log.error("DuplicateKeyException for document {} but no record found — replying FAILURE",
                            documentId);
                    processFailureCounter.increment();
                    sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                            "Duplicate key violation for document " + documentId + ": " + e.toString());
                }
                return null;
            });
            // Always throw so Spring does not try to commit the ROLLBACK_ONLY outer
            // transaction (which would raise UnexpectedRollbackException past SagaCommandHandler).
            throw new TaxInvoiceProcessingException("Concurrent insert for document: " + documentId, e);
        } catch (DataIntegrityViolationException e) {
            // Other constraint violations (value-too-long, check-constraint, etc.).
            // These are not race-condition duplicates and must not be treated as idempotent.
            processFailureCounter.increment();
            log.error("Constraint violation (non-duplicate-key) for document {}, saga {}: {}",
                    documentId, sagaId, e.toString());
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Constraint violation for document " + documentId + ": " + e.toString());
            throw new TaxInvoiceProcessingException(
                    "Constraint violation for document " + documentId, e);
        } catch (Exception e) {
            processFailureCounter.increment();
            // publishFailure uses REQUIRES_NEW propagation — commits in its own independent
            // transaction even if the outer transaction is ROLLBACK_ONLY or the Hibernate
            // session is invalid.
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Processing error for document " + documentId + ": " + e.toString());
            throw new TaxInvoiceProcessingException(
                    "Failed to process tax invoice " + documentId + ": " + e.toString(), e);
        } finally {
            sample.stop(processingTimer);
        }
    }

    private ProcessedTaxInvoice processInvoiceForSagaInternal(String documentId, String xmlContent,
                                                            String sagaId, SagaStep sagaStep, String correlationId)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        log.info("Processing tax invoice for saga, document: {}", documentId);

        // Idempotency check — also resumes partial-failure where a previous attempt
        // saved the entity in PROCESSING state but died before reaching COMPLETED.
        Optional<ProcessedTaxInvoice> existing = invoiceRepository.findBySourceInvoiceId(documentId);
        if (existing.isPresent()) {
            ProcessedTaxInvoice existingInvoice = existing.get();

            if (existingInvoice.getStatus() == ProcessingStatus.COMPLETED) {
                // True idempotent case: a prior attempt fully committed this document.
                log.warn("Tax invoice already completed for document {}, returning idempotent success", documentId);
                processIdempotentCounter.increment();
                sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
                return existingInvoice;
            }

            if (existingInvoice.getStatus() == ProcessingStatus.PROCESSING) {
                // Partial failure: entity was inserted (PROCESSING) but the attempt died
                // before markCompleted() + second save could commit. Resume from here
                // without re-parsing or re-inserting — avoids a duplicate-key violation
                // and ensures the orchestrator always receives a SUCCESS reply.
                log.warn("Tax invoice for document {} found in PROCESSING state — previous attempt "
                        + "failed mid-flight; resuming completion", documentId);
                existingInvoice.markCompleted();
                invoiceRepository.save(existingInvoice);
                TaxInvoiceProcessedDomainEvent domainEvent = new TaxInvoiceProcessedDomainEvent(
                    existingInvoice.getId(),
                    existingInvoice.getInvoiceNumber(),
                    existingInvoice.getTotal(),
                    sagaId,
                    correlationId,
                    Instant.now()
                );
                eventPublisher.publish(domainEvent);
                sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
                processSuccessCounter.increment();
                log.info("Resumed and completed tax invoice: {}", existingInvoice.getInvoiceNumber());
                return existingInvoice;
            }

            // PENDING is never persisted; FAILED not yet implemented.
            // Surface unexpected state as a clear error rather than silently mis-routing.
            throw new IllegalStateException(
                "Tax invoice for document " + documentId + " has unexpected persisted status: "
                    + existingInvoice.getStatus());
        }

        // Parse XML to domain model
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, documentId);

        // State: PENDING → PROCESSING
        invoice.startProcessing();

        // Save - direct call (race conditions handled by Camel retry if they occur)
        ProcessedTaxInvoice saved = invoiceRepository.save(invoice);

        log.info("Saved processed tax invoice: {}", saved.getInvoiceNumber());

        // State: PROCESSING → COMPLETED
        saved.markCompleted();
        invoiceRepository.save(saved);

        // Publish notification event (kept for notification-service)
        TaxInvoiceProcessedDomainEvent domainEvent = new TaxInvoiceProcessedDomainEvent(
            saved.getId(),
            saved.getInvoiceNumber(),
            saved.getTotal(),
            sagaId,
            correlationId,
            Instant.now()
        );
        eventPublisher.publish(domainEvent);

        // Publish saga success reply
        sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);

        processSuccessCounter.increment();
        log.info("Successfully processed tax invoice: {}", saved.getInvoiceNumber());
        return saved;
    }

    /**
     * Compensate/rollback a previously processed tax invoice.
     */
    @Override
    @Transactional
    public void compensate(String documentId, String sagaId, SagaStep sagaStep, String correlationId) {
        log.info("Compensating tax invoice for document: {}", documentId);

        try {
            Optional<ProcessedTaxInvoice> existing = invoiceRepository.findBySourceInvoiceId(documentId);
            if (existing.isPresent()) {
                invoiceRepository.deleteById(existing.get().getId());
                log.info("Deleted tax invoice for document: {}", documentId);
            } else {
                // Invoice already absent — duplicate compensation command (orchestrator retry or bug).
                compensateIdempotentCounter.increment();
                log.warn("Tax invoice not found for compensation of document {} saga {} — "
                    + "treating as idempotent duplicate (already compensated or never processed)",
                    documentId, sagaId);
            }

            sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
            compensateSuccessCounter.increment();
        } catch (Exception e) {
            compensateFailureCounter.increment();
            log.error("Failed to compensate tax invoice for saga {}: {}", sagaId, e.toString(), e);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Compensation failed: " + e.toString());
            // Rethrow so Camel receives a clean exception and triggers DLC retry,
            // rather than Spring throwing UnexpectedRollbackException when it tries
            // to commit the ROLLBACK_ONLY outer transaction after a silent return.
            // deleteById is idempotent (no-op if entity is absent), so retries are safe.
            throw new CompensateTaxInvoiceUseCase.TaxInvoiceCompensationException(
                    "Compensation failed for document " + documentId, e);
        }
    }

}
