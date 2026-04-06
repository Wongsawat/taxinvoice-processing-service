package com.wpanther.taxinvoice.processing.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when tax invoice processing is completed.
 * This is a trace event for audit/notification purposes.
 * Published to Kafka topic: taxinvoice.processed
 */
@Getter
public class TaxInvoiceProcessedEvent extends TraceEvent {

    private static final String EVENT_TYPE = "taxinvoice.processed";
    private static final String SOURCE = "taxinvoice-processing-service";
    private static final String TRACE_TYPE = "INVOICE_PROCESSED";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("total")
    private final BigDecimal total;

    @JsonProperty("currency")
    private final String currency;

    /**
     * Convenience constructor for creating the event.
     *
     * @param documentId     the processed document ID
     * @param documentNumber the document number
     * @param total          the invoice grand total
     * @param currency       the currency code
     * @param sagaId         the saga orchestration instance ID
     * @param correlationId  the end-to-end correlation ID from the originating request
     */
    public TaxInvoiceProcessedEvent(String documentId, String documentNumber, BigDecimal total,
                                     String currency, String sagaId, String correlationId) {
        super(sagaId, correlationId, SOURCE, TRACE_TYPE, null);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.total = total;
        this.currency = currency;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @JsonCreator
    public TaxInvoiceProcessedEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("version") int version,
        @JsonProperty("sagaId") String sagaId,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("source") String source,
        @JsonProperty("traceType") String traceType,
        @JsonProperty("context") String context,
        @JsonProperty("documentId") String documentId,
        @JsonProperty("documentNumber") String documentNumber,
        @JsonProperty("total") BigDecimal total,
        @JsonProperty("currency") String currency
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.total = total;
        this.currency = currency;
    }
}
