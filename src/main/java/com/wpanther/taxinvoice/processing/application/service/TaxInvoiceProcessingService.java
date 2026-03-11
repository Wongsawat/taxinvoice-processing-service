package com.wpanther.taxinvoice.processing.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedEvent;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Application service for tax invoice processing.
 * Implements inbound ports for processing and compensation.
 * Uses outbound ports via EventPublisher for event publishing and SagaReplyPort for saga replies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceProcessingService implements ProcessTaxInvoiceUseCase, CompensateTaxInvoiceUseCase {

    private final ProcessedTaxInvoiceRepository invoiceRepository;
    private final TaxInvoiceParserPort parserService;
    private final EventPublisher eventPublisher;
    private final SagaReplyPort sagaReplyPort;

    /**
     * Process tax invoice as part of a saga command.
     * Parses XML, validates, calculates totals, saves to DB, publishes notification event.
     */
    @Override
    @Transactional
    public void process(String documentId, String xmlContent,
                         String sagaId, SagaStep sagaStep, String correlationId) throws TaxInvoiceProcessingException {
        try {
            processInvoiceForSagaInternal(documentId, xmlContent, sagaId, sagaStep, correlationId);
        } catch (TaxInvoiceParserPort.TaxInvoiceParsingException e) {
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Parse error: " + e.getMessage());
            throw new TaxInvoiceProcessingException("Failed to parse tax invoice: " + e.getMessage(), e);
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
        eventPublisher.publishTaxInvoiceProcessed(processedEvent);

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
        } catch (Exception e) {
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
