package com.wpanther.taxinvoice.processing.domain.port;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Port for publishing saga reply events.
 */
public interface SagaReplyPort {

    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId);

    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage);

    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}
