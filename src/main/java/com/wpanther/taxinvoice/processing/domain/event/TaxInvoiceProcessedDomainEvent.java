package com.wpanther.taxinvoice.processing.domain.event;

import com.wpanther.taxinvoice.processing.domain.model.Money;

import java.time.Instant;

/**
 * Domain event raised when tax invoice processing completes.
 * Pure Java record — no framework or infrastructure dependencies.
 * The application layer translates this into a Kafka DTO via TaxInvoiceEventPublishingPort.
 *
 * <p>Use the static factory {@link #of} in production code so the field-to-argument
 * mapping is visible at the call site and {@code occurredAt} is stamped exactly once.
 * The canonical constructor is available for tests that require a fixed timestamp.
 */
public record TaxInvoiceProcessedDomainEvent(
    String documentId,
    String documentNumber,
    Money total,
    String sagaId,
    String correlationId,
    Instant occurredAt
) {
    public static TaxInvoiceProcessedDomainEvent of(
            String documentId,
            String documentNumber,
            Money total,
            String sagaId,
            String correlationId) {
        return new TaxInvoiceProcessedDomainEvent(documentId, documentNumber, total, sagaId, correlationId, Instant.now());
    }
}
