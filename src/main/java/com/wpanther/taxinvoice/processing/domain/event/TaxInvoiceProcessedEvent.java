package com.wpanther.taxinvoice.processing.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonProperty("invoiceId")
    private final String invoiceId;

    @JsonProperty("invoiceNumber")
    private final String invoiceNumber;

    @JsonProperty("total")
    private final BigDecimal total;

    @JsonProperty("currency")
    private final String currency;

    /**
     * Convenience constructor for creating the event.
     * The correlationId is stored as sagaId in the TraceEvent.
     */
    public TaxInvoiceProcessedEvent(String invoiceId, String invoiceNumber, BigDecimal total,
                                     String currency, String correlationId) {
        super(correlationId, SOURCE, TRACE_TYPE, null);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.total = total;
        this.currency = currency;
    }

    /**
     * Returns the correlation ID (stored as sagaId in TraceEvent).
     */
    @JsonIgnore
    public String getCorrelationId() {
        return getSagaId();
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
        @JsonProperty("source") String source,
        @JsonProperty("traceType") String traceType,
        @JsonProperty("context") String context,
        @JsonProperty("invoiceId") String invoiceId,
        @JsonProperty("invoiceNumber") String invoiceNumber,
        @JsonProperty("total") BigDecimal total,
        @JsonProperty("currency") String currency
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, source, traceType, context);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.total = total;
        this.currency = currency;
    }
}
