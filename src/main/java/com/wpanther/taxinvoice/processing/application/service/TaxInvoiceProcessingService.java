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
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
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
            .description("Number of DataIntegrityViolationExceptions resolved as concurrent duplicate inserts")
            .register(meterRegistry);
        this.compensateSuccessCounter = Counter.builder("taxinvoice.compensation.success")
            .description("Number of successful compensations")
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
            processSuccessCounter.increment();
        } catch (TaxInvoiceParserPort.TaxInvoiceParsingException e) {
            processFailureCounter.increment();
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Parse error: " + e.getMessage());
            throw new TaxInvoiceProcessingException("Failed to parse tax invoice: " + e.getMessage(), e);
        } catch (DataIntegrityViolationException e) {
            // Race condition: the outer transaction is ROLLBACK_ONLY after the constraint
            // violation. Re-check in a fresh REQUIRES_NEW transaction: if the document now
            // exists, a concurrent thread committed it first and we must reply SUCCESS —
            // not FAILURE — so the orchestrator is not tricked into compensating already-
            // committed work.
            // Note: do NOT increment any counter here — increment only after the re-check
            // determines the actual outcome to avoid double-counting.
            log.warn("DataIntegrityViolationException for document {}, saga {} — re-checking for concurrent insert",
                    documentId, sagaId);
            requiresNewTemplate.execute(txStatus -> {
                Optional<ProcessedTaxInvoice> existing = invoiceRepository.findBySourceInvoiceId(documentId);
                if (existing.isPresent()) {
                    // Concurrent thread committed the same document first; treat as idempotent success.
                    log.warn("Race condition resolved: document {} already committed by concurrent thread — replying SUCCESS",
                            documentId);
                    processConcurrentDuplicateCounter.increment();
                    // publishSuccess uses MANDATORY and joins this REQUIRES_NEW transaction atomically
                    sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
                } else {
                    // No record found — genuine constraint violation from another cause
                    log.error("DataIntegrityViolationException for document {} but no record found — replying FAILURE",
                            documentId);
                    processFailureCounter.increment();
                    sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                            "Duplicate document: " + e.getMessage());
                }
                return null;
            });
            // Always throw so Spring does not try to commit the ROLLBACK_ONLY outer
            // transaction (which would raise UnexpectedRollbackException past SagaCommandHandler).
            throw new TaxInvoiceProcessingException("Concurrent insert for document: " + documentId, e);
        } catch (Exception e) {
            processFailureCounter.increment();
            // publishFailure uses REQUIRES_NEW propagation — commits in its own independent
            // transaction even if the outer transaction is ROLLBACK_ONLY or the Hibernate
            // session is invalid.
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Processing error for document " + documentId + ": " + e.getMessage());
            throw new TaxInvoiceProcessingException(
                    "Failed to process tax invoice " + documentId + ": " + e.getMessage(), e);
        } finally {
            sample.stop(processingTimer);
        }
    }

    private ProcessedTaxInvoice processInvoiceForSagaInternal(String documentId, String xmlContent,
                                                            String sagaId, SagaStep sagaStep, String correlationId)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        log.info("Processing tax invoice for saga, document: {}", documentId);

        // Idempotency check
        Optional<ProcessedTaxInvoice> existing = invoiceRepository.findBySourceInvoiceId(documentId);
        if (existing.isPresent()) {
            log.warn("Tax invoice already processed for document {}, returning existing", documentId);
            processIdempotentCounter.increment();
            // Still publish success for idempotent case
            sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
            return existing.get();
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
                log.warn("Tax invoice not found for compensation: {}", documentId);
            }

            sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
            compensateSuccessCounter.increment();
        } catch (Exception e) {
            compensateFailureCounter.increment();
            log.error("Failed to compensate tax invoice for saga {}: {}", sagaId, e.getMessage(), e);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Compensation failed: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    Optional<ProcessedTaxInvoice> findById(String id) {
        try {
            return invoiceRepository.findById(TaxInvoiceId.from(id));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tax invoice ID format: {}", id);
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status) {
        return invoiceRepository.findByStatus(status);
    }
}
