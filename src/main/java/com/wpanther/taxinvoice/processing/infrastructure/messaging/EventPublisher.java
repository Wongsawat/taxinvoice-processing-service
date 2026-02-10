package com.wpanther.taxinvoice.processing.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedEvent;
import com.wpanther.taxinvoice.processing.domain.event.XmlSigningRequestedEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTaxInvoiceProcessed(TaxInvoiceProcessedEvent event) {
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
            toJson(headers)
        );

        log.info("Published TaxInvoiceProcessedEvent to outbox: {}", event.getInvoiceNumber());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishXmlSigningRequested(XmlSigningRequestedEvent event) {
        Map<String, String> headers = Map.of(
            "correlationId", event.getCorrelationId(),
            "documentType", event.getDocumentType()
        );

        outboxService.saveWithRouting(
            event,
            "ProcessedTaxInvoice",
            event.getInvoiceId(),
            "xml.signing.requested",
            event.getInvoiceId(),
            toJson(headers)
        );

        log.info("Published XmlSigningRequestedEvent to outbox: {}", event.getInvoiceNumber());
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers to JSON", e);
            return null;
        }
    }
}
