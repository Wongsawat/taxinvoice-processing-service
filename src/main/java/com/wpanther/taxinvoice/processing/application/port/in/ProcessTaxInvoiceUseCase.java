package com.wpanther.taxinvoice.processing.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;

/**
 * Inbound port for processing tax invoices.
 * This is the use case interface that defines the primary action of this service.
 */
public interface ProcessTaxInvoiceUseCase {

    /**
     * Process a tax invoice as part of a saga command.
     * Parses XML, validates, calculates totals, saves to DB, publishes notification event.
     * Handles idempotency, parses XML, persists, raises domain events, publishes saga reply.
     *
     * @param documentId    Source document ID (used for idempotency)
     * @param xmlContent    Raw XML string to parse
     * @param sagaId        Saga instance identifier
     * @param sagaStep      Current step in the saga
     * @param correlationId Correlation ID for tracing
     */
    void process(String documentId, String xmlContent,
                  String sagaId, SagaStep sagaStep, String correlationId) throws TaxInvoiceProcessingException;

    /**
     * Exception thrown when tax invoice processing fails
     */
    class TaxInvoiceProcessingException extends Exception {
        public TaxInvoiceProcessingException(String message) {
            super(message);
        }

        public TaxInvoiceProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
