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
            // Reply already published by use case on failure
            log.error("Failed to process tax invoice for saga {}: {}",
                command.getSagaId(), e.getMessage(), e);
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
