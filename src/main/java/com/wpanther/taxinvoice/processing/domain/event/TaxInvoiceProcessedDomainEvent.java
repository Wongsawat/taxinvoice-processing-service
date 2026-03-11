package com.wpanther.taxinvoice.processing.domain.event;

import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;

import java.time.Instant;

/**
 * Domain event raised by ProcessedTaxInvoice when processing completes.
 * Pure Java record — no framework or Kafka dependencies.
 * The application layer translates this into a Kafka DTO via TaxInvoiceEventPublishingPort.
 */
public record TaxInvoiceProcessedDomainEvent(
    TaxInvoiceId invoiceId,
    String invoiceNumber,
    Money total,
    String correlationId,
    Instant occurredAt
) {}
