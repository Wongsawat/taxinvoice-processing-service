package com.wpanther.taxinvoice.processing.application.port.out;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Outbound port for publishing saga reply events.
 * Implemented by infrastructure adapters that send replies to the saga orchestrator.
 */
public interface SagaReplyPort {

    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId);

    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage);

    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}
