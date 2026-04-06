package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.taxinvoice.processing.application.dto.event.TaxInvoiceProcessedEvent;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import com.wpanther.taxinvoice.processing.infrastructure.config.KafkaTopicsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Production constructor — Spring injects the bound {@link KafkaTopicsProperties}. */
    @Autowired
    public TaxInvoiceEventPublisher(
            OutboxService outboxService,
            HeaderSerializer headerSerializer,
            KafkaTopicsProperties topics) {
        this(outboxService, headerSerializer, topics.taxinvoiceProcessed());
    }

    /** Package-private constructor for unit tests that pass the topic string directly. */
    TaxInvoiceEventPublisher(OutboxService outboxService, HeaderSerializer headerSerializer,
                             String taxinvoiceProcessedTopic) {
        this.outboxService = outboxService;
        this.headerSerializer = headerSerializer;
        this.taxinvoiceProcessedTopic = taxinvoiceProcessedTopic;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(TaxInvoiceProcessedDomainEvent domainEvent) {
        // Transform: domain event → Kafka event
        TaxInvoiceProcessedEvent kafkaEvent = new TaxInvoiceProcessedEvent(
            domainEvent.documentId(),
            domainEvent.documentNumber(),
            domainEvent.total().amount(),
            domainEvent.total().currency(),
            domainEvent.sagaId(),
            domainEvent.correlationId()
        );

        Map<String, String> headers = Map.of(
            "correlationId", domainEvent.correlationId(),
            "documentNumber", domainEvent.documentNumber()
        );

        outboxService.saveWithRouting(
            kafkaEvent,
            "ProcessedTaxInvoice",
            domainEvent.documentId(),
            taxinvoiceProcessedTopic,
            domainEvent.documentId(),
            headerSerializer.toJson(headers)
        );

        log.info("Published TaxInvoiceProcessedEvent to outbox: {}", domainEvent.documentNumber());
    }
}
