package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging;

import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SagaCommandHandler
 * Tests that the handler correctly delegates to use cases.
 */
@ExtendWith(MockitoExtension.class)
class SagaCommandHandlerTest {

    @Mock
    private ProcessTaxInvoiceUseCase processTaxInvoiceUseCase;

    @Mock
    private CompensateTaxInvoiceUseCase compensateTaxInvoiceUseCase;

    @InjectMocks
    private SagaCommandHandler sagaCommandHandler;

    private ProcessTaxInvoiceCommand validCommand;
    private CompensateTaxInvoiceCommand compensateCommand;

    @BeforeEach
    void setUp() {
        validCommand = new ProcessTaxInvoiceCommand(
            "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1", "doc-001", "<xml/>", "TV-001"
        );

        compensateCommand = new CompensateTaxInvoiceCommand(
            "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1", "process-tax-invoice", "doc-001", "tax-invoice"
        );
    }

    @Test
    void shouldDelegateToProcessTaxInvoiceUseCase() throws Exception {
        // Given
        doNothing().when(processTaxInvoiceUseCase).process(any(), any(), any(), any(), any());

        // When
        sagaCommandHandler.handleProcessCommand(validCommand);

        // Then
        verify(processTaxInvoiceUseCase).process(
            eq("doc-001"),
            eq("<xml/>"),
            eq("saga-1"),
            eq(SagaStep.PROCESS_TAX_INVOICE),
            eq("corr-1")
        );
        verify(compensateTaxInvoiceUseCase, never()).compensate(any(), any(), any(), any());
    }

    @Test
    void shouldDelegateToCompensateTaxInvoiceUseCase() throws Exception {
        // Given
        doNothing().when(compensateTaxInvoiceUseCase).compensate(any(), any(), any(), any());

        // When
        sagaCommandHandler.handleCompensation(compensateCommand);

        // Then
        verify(compensateTaxInvoiceUseCase).compensate(
            eq("doc-001"),
            eq("saga-1"),
            eq(SagaStep.PROCESS_TAX_INVOICE),
            eq("corr-1")
        );
        verify(processTaxInvoiceUseCase, never()).process(any(), any(), any(), any(), any());
    }

    @Test
    void shouldPropagateExceptionFromProcessUseCase() throws Exception {
        // Given
        doThrow(new ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException("Processing error"))
            .when(processTaxInvoiceUseCase).process(any(), any(), any(), any(), any());

        // When - exception is caught and logged by handler
        assertDoesNotThrow(() -> sagaCommandHandler.handleProcessCommand(validCommand));

        verify(processTaxInvoiceUseCase).process(any(), any(), any(), any(), any());
    }

    @Test
    void shouldPropagateExceptionFromCompensateUseCase() throws Exception {
        // Given - use case publishes FAILURE reply then throws TaxInvoiceCompensationException
        doThrow(new CompensateTaxInvoiceUseCase.TaxInvoiceCompensationException(
                "Compensation failed", new RuntimeException("DB error")))
            .when(compensateTaxInvoiceUseCase).compensate(any(), any(), any(), any());

        // When/Then - exception propagates to Camel so the DLC can retry
        assertThrows(CompensateTaxInvoiceUseCase.TaxInvoiceCompensationException.class,
            () -> sagaCommandHandler.handleCompensation(compensateCommand));

        verify(compensateTaxInvoiceUseCase).compensate(any(), any(), any(), any());
    }
}
