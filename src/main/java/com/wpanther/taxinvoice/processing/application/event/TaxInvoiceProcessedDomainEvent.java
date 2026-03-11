package com.wpanther.taxinvoice.processing.application.event;

import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;

import java.time.Instant;

/**
 * Application event raised when a tax invoice is processed.
 * Pure Java record — no framework or Kafka dependencies.
 * The application layer publishes this via TaxInvoiceEventPublishingPort.
 */
public record TaxInvoiceProcessedDomainEvent(
    TaxInvoiceId invoiceId,
    String invoiceNumber,
    Money total,
    String correlationId,
    Instant occurredAt
) {}
