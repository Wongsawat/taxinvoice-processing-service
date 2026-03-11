package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.taxinvoice.processing.application.event.TaxInvoiceProcessedDomainEvent;

/**
 * Outbound port for publishing tax invoice processed events.
 * Implemented by infrastructure adapters that publish domain events.
 */
public interface TaxInvoiceEventPublishingPort {

    /**
     * Publish a tax invoice processed domain event.
     * @param domainEvent the domain event to publish
     */
    void publish(TaxInvoiceProcessedDomainEvent domainEvent);
}
