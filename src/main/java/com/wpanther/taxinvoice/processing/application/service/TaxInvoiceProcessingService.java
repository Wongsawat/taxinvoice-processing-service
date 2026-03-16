package com.wpanther.taxinvoice.processing.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Application service for tax invoice processing.
 * Implements inbound ports for processing and compensation.
 * Uses outbound ports via TaxInvoiceEventPublishingPort for event publishing and SagaReplyPort for saga replies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceProcessingService implements ProcessTaxInvoiceUseCase, CompensateTaxInvoiceUseCase {

    private final ProcessedTaxInvoiceRepository invoiceRepository;
    private final TaxInvoiceParserPort parserService;
    private final TaxInvoiceEventPublishingPort eventPublisher;
    private final SagaReplyPort sagaReplyPort;
    private final MeterRegistry meterRegistry;

    private Counter getProcessSuccessCounter() {
        return Counter.builder("taxinvoice.processing.success")
            .description("Number of successfully processed tax invoices")
            .register(meterRegistry);
    }

    private Counter getProcessFailureCounter() {
        return Counter.builder("taxinvoice.processing.failure")
            .description("Number of failed tax invoice processing attempts")
            .register(meterRegistry);
    }

    private Counter getCompensateSuccessCounter() {
        return Counter.builder("taxinvoice.compensation.success")
            .description("Number of successful compensations")
            .register(meterRegistry);
    }

    private Counter getCompensateFailureCounter() {
        return Counter.builder("taxinvoice.compensation.failure")
            .description("Number of failed compensation attempts")
            .register(meterRegistry);
    }

    private Timer getProcessingTimer() {
        return Timer.builder("taxinvoice.processing.duration")
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
        try {
            getProcessingTimer().record(() -> {
                try {
                    processInvoiceForSagaInternal(documentId, xmlContent, sagaId, sagaStep, correlationId);
                    getProcessSuccessCounter().increment();
                } catch (TaxInvoiceParserPort.TaxInvoiceParsingException e) {
                    getProcessFailureCounter().increment();
                    throw new CompletionException(e);
                } catch (RuntimeException e) {
                    getProcessFailureCounter().increment();
                    throw new CompletionException(e);  // Wrap to ensure outer catch handles it
                }
            });
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TaxInvoiceParserPort.TaxInvoiceParsingException parseException) {
                sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Parse error: " + parseException.getMessage());
                throw new TaxInvoiceProcessingException("Failed to parse tax invoice: " + parseException.getMessage(), parseException);
            } else if (cause instanceof RuntimeException runtimeException) {
                // For unexpected runtime errors (DB failures, etc.), publish failure reply to avoid hanging the saga
                // Use publishFailure with REQUIRES_NEW to ensure the reply is sent even if the transaction rolls back
                sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Processing error: " + runtimeException.getMessage());
                throw new TaxInvoiceProcessingException("Failed to process tax invoice: " + runtimeException.getMessage(), runtimeException);
            } else {
                // Unknown error type - publish failure and wrap
                sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Unexpected error: " + (cause != null ? cause.getMessage() : "unknown cause"));
                throw new TaxInvoiceProcessingException("Failed to process tax invoice: " + (cause != null ? cause.getMessage() : "unknown cause"), e);
            }
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
            // Still publish success for idempotent case
            sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId);
            return existing.get();
        }

        // Parse XML to domain model
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, documentId);

        // State: PENDING → PROCESSING
        invoice.startProcessing();
        ProcessedTaxInvoice saved = invoiceRepository.save(invoice);
        log.info("Saved processed tax invoice: {}", saved.getInvoiceNumber());

        // State: PROCESSING → COMPLETED
        saved.markCompleted(correlationId);
        invoiceRepository.save(saved);

        // Publish notification event (kept for notification-service)
        TaxInvoiceProcessedEvent processedEvent = new TaxInvoiceProcessedEvent(
            saved.getId().toString(),
            saved.getInvoiceNumber(),
            saved.getTotal().amount(),
            saved.getCurrency(),
            correlationId
        );
        eventPublisher.publish(processedEvent);

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
            getCompensateSuccessCounter().increment();
        } catch (Exception e) {
            getCompensateFailureCounter().increment();
            log.error("Failed to compensate tax invoice for saga {}: {}", sagaId, e.getMessage(), e);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Compensation failed: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<ProcessedTaxInvoice> findById(String id) {
        try {
            return invoiceRepository.findById(TaxInvoiceId.from(id));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tax invoice ID format: {}", id);
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status) {
        return invoiceRepository.findByStatus(status);
    }
}
