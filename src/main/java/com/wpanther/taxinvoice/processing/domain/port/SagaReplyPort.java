package com.wpanther.taxinvoice.processing.domain.port;

/**
 * Output port for publishing saga reply messages.
 * Decouples SagaCommandHandler (application layer) from Kafka/Outbox infrastructure.
 */
public interface SagaReplyPort {

    void publishSuccess(String sagaId, String sagaStep, String correlationId);

    void publishFailure(String sagaId, String sagaStep, String correlationId, String errorMessage);

    void publishCompensated(String sagaId, String sagaStep, String correlationId);
}
