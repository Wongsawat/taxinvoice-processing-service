package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging;

import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Saga command handler - driving adapter that receives Kafka messages and calls use cases.
 * Routes ProcessTaxInvoiceCommand → ProcessTaxInvoiceUseCase.process()
 * Routes CompensateTaxInvoiceCommand → CompensateTaxInvoiceUseCase.compensate()
 * The use case handles reply publishing internally.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final ProcessTaxInvoiceUseCase processTaxInvoiceUseCase;
    private final CompensateTaxInvoiceUseCase compensateTaxInvoiceUseCase;

    /**
     * Handle a ProcessTaxInvoiceCommand from saga orchestrator.
     * Delegates to use case which handles business logic and reply publishing.
     *
     * <h3>Exception contract</h3>
     * <p>{@link ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException} is thrown by
     * {@code process()} <em>only after</em> the saga reply (SUCCESS or FAILURE) has been
     * successfully committed to the outbox table. Catching it here and returning normally
     * tells Camel "this message is done" — the offset is committed and the orchestrator
     * already has the reply.
     *
     * <p>Any <em>other</em> exception (e.g., a transient DB outage that prevented the
     * outbox write inside {@code publishFailure()} or {@code publishSuccess()}) is
     * intentionally <em>not</em> caught. It propagates to the Camel dead-letter channel,
     * which retries the message. On retry the idempotency check inside
     * {@code processInvoiceForSagaInternal} either finds an already-persisted invoice and
     * publishes SUCCESS, or reruns the processing. This ensures the orchestrator always
     * receives a reply and the Kafka offset is only committed once the reply is durable.
     */
    public void handleProcessCommand(ProcessTaxInvoiceCommand command) {
        log.info("Handling ProcessTaxInvoiceCommand for saga {} document {}",
            command.getSagaId(), command.getDocumentId());

        try {
            processTaxInvoiceUseCase.process(
                command.getDocumentId(),
                command.getXmlContent(),
                command.getSagaId(),
                command.getSagaStep(),
                command.getCorrelationId()
            );
        } catch (ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException e) {
            // Reply was already committed to the outbox by the use case.
            // Return normally so Camel commits the Kafka offset.
            log.error("Failed to process tax invoice for saga {}: {}",
                command.getSagaId(), e.toString(), e);
        }
    }

    /**
     * Handle a CompensateTaxInvoiceCommand from saga orchestrator.
     * Delegates to use case which handles business logic and reply publishing.
     */
    public void handleCompensation(CompensateTaxInvoiceCommand command) {
        log.info("Handling compensation for saga {} document {}",
            command.getSagaId(), command.getDocumentId());

        compensateTaxInvoiceUseCase.compensate(
            command.getDocumentId(),
            command.getSagaId(),
            command.getSagaStep(),
            command.getCorrelationId()
        );
    }
}
