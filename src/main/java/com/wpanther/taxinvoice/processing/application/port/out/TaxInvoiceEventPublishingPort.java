package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;

/**
 * Outbound port for publishing tax invoice processed events.
 * Application layer publishes the TaxInvoiceProcessedDomainEvent (pure domain event).
 * Implementation: infrastructure/adapter/out/messaging/TaxInvoiceEventPublisher.
 * The adapter translates the pure domain event into a Kafka DTO before writing to the outbox.
 */
public interface TaxInvoiceEventPublishingPort {

    /**
     * Publish a domain event indicating a tax invoice was successfully processed.
     * Must be called within an active transaction (MANDATORY propagation on the adapter).
     */
    void publish(TaxInvoiceProcessedDomainEvent event);
}
