package com.wpanther.taxinvoice.processing.domain.event;

import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.Money;

import java.time.Instant;

/**
 * Domain event raised when tax invoice processing completes.
 * Pure Java record — no framework or infrastructure dependencies.
 * The application layer translates this into a Kafka DTO via TaxInvoiceEventPublishingPort.
 */
public record TaxInvoiceProcessedDomainEvent(
    TaxInvoiceId invoiceId,
    String invoiceNumber,
    Money total,
    String correlationId,
    Instant occurredAt
) {}
