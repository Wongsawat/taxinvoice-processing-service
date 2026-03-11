package com.wpanther.taxinvoice.processing.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for compensating (rolling back) tax invoice processing.
 * This is the compensation interface for saga rollback.
 */
public interface CompensateTaxInvoiceUseCase {

    /**
     * Compensate/rollback a previously processed tax invoice.
     * This is called when a downstream saga step fails and this step needs to be rolled back.
     *
     * @param documentId    The source document ID to compensate
     * @param sagaId       Saga instance identifier
     * @param sagaStep     Current step in the saga
     * @param correlationId Correlation ID for tracing
     */
    void compensate(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
