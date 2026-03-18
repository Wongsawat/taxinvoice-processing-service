package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.taxinvoice.processing.application.dto.event.TaxInvoiceProcessedEvent;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Tax Invoice Event Publisher - driven adapter that publishes events to Kafka via outbox pattern.
 * Implements TaxInvoiceEventPublishingPort to adhere to hexagonal architecture.
 */
@Component
@Slf4j
public class TaxInvoiceEventPublisher implements TaxInvoiceEventPublishingPort {

    private final OutboxService outboxService;
    private final HeaderSerializer headerSerializer;
    private final String taxinvoiceProcessedTopic;

    public TaxInvoiceEventPublisher(
            OutboxService outboxService,
            HeaderSerializer headerSerializer,
            @Value("${app.kafka.topics.taxinvoice-processed}") String taxinvoiceProcessedTopic) {
        this.outboxService = outboxService;
        this.headerSerializer = headerSerializer;
        this.taxinvoiceProcessedTopic = taxinvoiceProcessedTopic;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(TaxInvoiceProcessedDomainEvent domainEvent) {
        // Transform: domain event → Kafka event
        TaxInvoiceProcessedEvent kafkaEvent = new TaxInvoiceProcessedEvent(
            domainEvent.invoiceId().value().toString(),
            domainEvent.invoiceNumber(),
            domainEvent.total().amount(),
            domainEvent.total().currency(),
            domainEvent.sagaId(),
            domainEvent.correlationId()
        );

        Map<String, String> headers = Map.of(
            "correlationId", domainEvent.correlationId(),
            "invoiceNumber", domainEvent.invoiceNumber()
        );

        outboxService.saveWithRouting(
            kafkaEvent,
            "ProcessedTaxInvoice",
            domainEvent.invoiceId().value().toString(),
            taxinvoiceProcessedTopic,
            domainEvent.invoiceId().value().toString(),
            headerSerializer.toJson(headers)
        );

        log.info("Published TaxInvoiceProcessedEvent to outbox: {}", domainEvent.invoiceNumber());
    }
}
