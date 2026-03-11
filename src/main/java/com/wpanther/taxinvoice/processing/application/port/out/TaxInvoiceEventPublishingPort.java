package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;

/**
 * Outbound port for publishing tax invoice processed events to Kafka.
 * Implemented by infrastructure adapters that publish events via outbox pattern.
 */
public interface TaxInvoiceEventPublishingPort {

    /**
     * Publish a tax invoice processed event to Kafka.
     * @param event the Kafka event to publish
     */
    void publish(TaxInvoiceProcessedEvent event);
}
