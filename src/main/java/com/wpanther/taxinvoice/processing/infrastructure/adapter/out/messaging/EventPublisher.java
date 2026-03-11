package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Event publisher - driven adapter that publishes events to Kafka via outbox pattern.
 * Implements TaxInvoiceEventPublishingPort to adhere to hexagonal architecture.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher implements TaxInvoiceEventPublishingPort {

    private final OutboxService outboxService;
    private final HeaderSerializer headerSerializer;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(TaxInvoiceProcessedEvent event) {
        Map<String, String> headers = Map.of(
            "correlationId", event.getCorrelationId(),
            "invoiceNumber", event.getInvoiceNumber()
        );

        outboxService.saveWithRouting(
            event,
            "ProcessedTaxInvoice",
            event.getInvoiceId(),
            "taxinvoice.processed",
            event.getInvoiceId(),
            headerSerializer.toJson(headers)
        );

        log.info("Published TaxInvoiceProcessedEvent to outbox: {}", event.getInvoiceNumber());
    }
}
