package com.wpanther.taxinvoice.processing.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.CompensateTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.taxinvoice.processing.domain.model.*;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaxInvoiceProcessingService (saga version)
 */
@ExtendWith(MockitoExtension.class)
class TaxInvoiceProcessingServiceTest {

    @Mock
    private ProcessedTaxInvoiceRepository invoiceRepository;

    @Mock
    private TaxInvoiceParserPort parserService;

    @Mock
    private TaxInvoiceEventPublishingPort eventPublisher;

    @Mock
    private SagaReplyPort sagaReplyPort;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TaxInvoiceProcessingService service;

    private ProcessedTaxInvoice validInvoice;

    @BeforeEach
    void setUp() {
        service = new TaxInvoiceProcessingService(
            invoiceRepository,
            parserService,
            eventPublisher,
            sagaReplyPort,
            new SimpleMeterRegistry(),
            transactionManager
        );
        Party seller = Party.of(
            "Seller Company",
            TaxIdentifier.of("1234567890", "VAT"),
            new Address("123 Street", "Bangkok", "10110", "TH")
        );

        Party buyer = Party.of(
            "Buyer Company",
            TaxIdentifier.of("9876543210", "VAT"),
            new Address("456 Road", "Chiang Mai", "50000", "TH")
        );

        LineItem item = new LineItem(
            "Service 1",
            10,
            Money.of(new BigDecimal("1000.00"), "THB"),
            new BigDecimal("7.00")
        );

        validInvoice = ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.generate())
            .sourceInvoiceId("intake-123")
            .invoiceNumber("TXN-001")
            .issueDate(LocalDate.of(2025, 1, 1))
            .dueDate(LocalDate.of(2025, 2, 1))
            .seller(seller)
            .buyer(buyer)
            .addItem(item)
            .currency("THB")
            .originalXml("<xml>test</xml>")
            .build();
    }

    @Test
    void testProcessInvoiceForSagaSuccess() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class))).thenReturn(validInvoice);

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");

        // Then
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(parserService).parse("<xml>test</xml>", "intake-123");
        verify(invoiceRepository, times(2)).save(any(ProcessedTaxInvoice.class));
        verify(eventPublisher).publish(any(TaxInvoiceProcessedDomainEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");
    }

    @Test
    void testProcessInvoiceForSagaAlreadyProcessed() throws Exception {
        // Given — simulate a COMPLETED invoice already in DB (true idempotent case)
        Party seller = Party.of("Seller Company", TaxIdentifier.of("1234567890", "VAT"),
            new Address("123 Street", "Bangkok", "10110", "TH"));
        Party buyer = Party.of("Buyer Company", TaxIdentifier.of("9876543210", "VAT"),
            new Address("456 Road", "Chiang Mai", "50000", "TH"));
        LineItem item = new LineItem("Service 1", 10,
            Money.of(new BigDecimal("1000.00"), "THB"), new BigDecimal("7.00"));
        ProcessedTaxInvoice completedInvoice = ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.generate())
            .sourceInvoiceId("intake-123")
            .invoiceNumber("TXN-001")
            .issueDate(LocalDate.of(2025, 1, 1))
            .dueDate(LocalDate.of(2025, 2, 1))
            .seller(seller).buyer(buyer).addItem(item)
            .currency("THB").originalXml("<xml>test</xml>")
            .status(ProcessingStatus.COMPLETED)
            .build();
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.of(completedInvoice));

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");

        // Then — no re-processing; saga reply still published
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(parserService, never()).parse(anyString(), anyString());
        verify(invoiceRepository, never()).save(any(ProcessedTaxInvoice.class));
        verify(eventPublisher, never()).publish(any());
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");
    }

    @Test
    void testProcessInvoiceForSagaResumesCompletionFromProcessingState() throws Exception {
        // Given — previous attempt saved the entity in PROCESSING state but failed before
        // completing it. The retry delivers the same command.
        Party seller = Party.of("Seller Company", TaxIdentifier.of("1234567890", "VAT"),
            new Address("123 Street", "Bangkok", "10110", "TH"));
        Party buyer = Party.of("Buyer Company", TaxIdentifier.of("9876543210", "VAT"),
            new Address("456 Road", "Chiang Mai", "50000", "TH"));
        LineItem item = new LineItem("Service 1", 10,
            Money.of(new BigDecimal("1000.00"), "THB"), new BigDecimal("7.00"));
        ProcessedTaxInvoice processingInvoice = ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.generate())
            .sourceInvoiceId("intake-123")
            .invoiceNumber("TXN-001")
            .issueDate(LocalDate.of(2025, 1, 1))
            .dueDate(LocalDate.of(2025, 2, 1))
            .seller(seller).buyer(buyer).addItem(item)
            .currency("THB").originalXml("<xml>test</xml>")
            .status(ProcessingStatus.PROCESSING)
            .build();
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.of(processingInvoice));

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");

        // Then — no re-parsing, no re-inserting; entity completed and events published
        verify(parserService, never()).parse(anyString(), anyString());
        verify(invoiceRepository, times(1)).save(any(ProcessedTaxInvoice.class));
        verify(eventPublisher).publish(any(TaxInvoiceProcessedDomainEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");
        // Verify the domain object was actually transitioned to COMPLETED
        assertEquals(ProcessingStatus.COMPLETED, processingInvoice.getStatus());
    }

    @Test
    void testProcessInvoiceForSagaParsingError() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString()))
            .thenThrow(new TaxInvoiceParserPort.TaxInvoiceParsingException("Parse error"));

        // When / Then - the service wraps TaxInvoiceParsingException in TaxInvoiceProcessingException
        assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
            () -> service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));

        verify(parserService).parse("<xml>test</xml>", "intake-123");
        verify(invoiceRepository, never()).save(any(ProcessedTaxInvoice.class));
        verify(eventPublisher, never()).publish(any());
        // Verify failure reply is published
        verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE), eq("correlation-123"), contains("Parse error"));
    }

    @Test
    void testProcessInvoiceForSagaPublishesCorrectEvent() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class))).thenReturn(validInvoice);

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");

        // Then
        ArgumentCaptor<TaxInvoiceProcessedDomainEvent> eventCaptor =
            ArgumentCaptor.forClass(TaxInvoiceProcessedDomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());

        TaxInvoiceProcessedDomainEvent processedEvent = eventCaptor.getValue();
        assertEquals("TXN-001", processedEvent.documentNumber());
        assertEquals("THB", processedEvent.total().currency());
        assertEquals("correlation-123", processedEvent.correlationId());
    }

    @Test
    void testProcessInvoiceForSagaSavesTwice() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class))).thenReturn(validInvoice);

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");

        // Then - Should save twice: PROCESSING and COMPLETED
        verify(invoiceRepository, times(2)).save(any(ProcessedTaxInvoice.class));
    }

    @Test
    void testProcessInvoiceForSagaDatabaseError() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
            .thenThrow(new RuntimeException("Database error"));

        // When / Then
        assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
            () -> service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));

        // Verify failure reply is published to avoid hanging the saga
        verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE), eq("correlation-123"), contains("Processing error"));
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void testProcessInvoiceForSagaReturnsProcessedInvoice() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class))).thenReturn(validInvoice);

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        // Then
        // Verify processing was successful by checking repository calls
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(parserService).parse("<xml>test</xml>", "intake-123");
        verify(invoiceRepository, times(2)).save(any(ProcessedTaxInvoice.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");
    }

    @Test
    void testCompensateDeletesExistingInvoice() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId("intake-123")).thenReturn(Optional.of(validInvoice));

        // When
        service.compensate("intake-123", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        // Then
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(invoiceRepository).deleteById(validInvoice.getId());
        verify(sagaReplyPort).publishCompensated("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");
    }

    @Test
    void testCompensateNotFound() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId("intake-notfound")).thenReturn(Optional.empty());

        // When
        service.compensate("intake-notfound", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        // Then
        verify(invoiceRepository).findBySourceInvoiceId("intake-notfound");
        verify(invoiceRepository, never()).deleteById(any());
        verify(sagaReplyPort).publishCompensated("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");
    }

    @Test
    void testCompensateHandlesException() {
        // Given
        when(invoiceRepository.findBySourceInvoiceId("intake-123")).thenReturn(Optional.of(validInvoice));
        doThrow(new RuntimeException("DB error")).when(invoiceRepository).deleteById(any());

        // When/Then - exception is rethrown so Camel DLC can retry; FAILURE reply is still published
        assertThrows(CompensateTaxInvoiceUseCase.TaxInvoiceCompensationException.class,
            () -> service.compensate("intake-123", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1"));

        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(invoiceRepository).deleteById(any());
        verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE), eq("corr-1"), contains("Compensation failed"));
    }

    @Test
    void testProcessInvoiceForSagaDataIntegrityViolationPropagates() throws Exception {
        // Given - simulate the "ghost duplicate" scenario: idempotency check passes, insert
        // conflicts on uq_processed_tax_invoices_source_invoice_id, but the REQUIRES_NEW
        // re-check finds no record (the winning thread rolled back or never committed).
        // The exception must carry a SQLException with SQLState "23505" (ANSI unique_violation)
        // matching what the PostgreSQL JDBC driver wraps inside DuplicateKeyException.
        SQLException sqlCause = new SQLException(
            "ERROR: duplicate key value violates unique constraint" +
            " \"uq_processed_tax_invoices_source_invoice_id\"", "23505");
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
            .thenThrow(new DuplicateKeyException("duplicate key", sqlCause));

        // When / Then - exception propagates (no silent swallowing), with original cause preserved
        ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException ex =
            assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
                () -> service.process("intake-123", "<xml>test</xml>",
                                      "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));
        assertInstanceOf(DataIntegrityViolationException.class, ex.getCause());

        // publishFailure is attempted (in real Spring context with ROLLBACK_ONLY
        // transaction this write would be lost, but the call must still be made)
        verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE),
            eq("correlation-123"), anyString());

        // Domain event never published (invoice not committed)
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void testProcessInvoiceForSagaRaceConditionResolvesAsSuccess() throws Exception {
        // Given - race condition: idempotency check passes, insert conflicts on
        // uq_processed_tax_invoices_source_invoice_id, and the REQUIRES_NEW re-check
        // finds the document already committed by the concurrent thread → publishSuccess.
        // SQLException with SQLState "23505" is required by isSourceInvoiceIdViolation().
        SQLException sqlCause = new SQLException(
            "ERROR: duplicate key value violates unique constraint" +
            " \"uq_processed_tax_invoices_source_invoice_id\"", "23505");
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(invoiceRepository.findBySourceInvoiceId(anyString()))
            .thenReturn(Optional.empty())          // 1st call: idempotency check — no record yet
            .thenReturn(Optional.of(validInvoice)); // 2nd call: re-check — concurrent insert committed
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
            .thenThrow(new DuplicateKeyException("duplicate key", sqlCause));

        // When / Then — exception still propagates (prevents Spring UnexpectedRollbackException)
        ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException ex =
            assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
                () -> service.process("intake-123", "<xml>test</xml>",
                                      "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));
        assertInstanceOf(DataIntegrityViolationException.class, ex.getCause());

        // SUCCESS reply published because the document was found on re-check
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");
        verify(sagaReplyPort, never()).publishFailure(any(), any(), any(), any());

        // Domain event never published by this thread (the winning thread already did so)
        verify(eventPublisher, never()).publish(any());
    }

    /**
     * A plain DataIntegrityViolationException (value-too-long, check-constraint, etc.)
     * is NOT a DuplicateKeyException, so it must:
     *  - skip the race-condition re-check entirely (no second findBySourceInvoiceId call)
     *  - publish FAILURE with "Constraint violation:" prefix
     *  - increment processFailureCounter, not processRaceConditionResolvedCounter
     *  - throw TaxInvoiceProcessingException immediately
     */
    @Test
    void testProcessInvoiceForSagaNonDuplicateKeyConstraintViolation() throws Exception {
        // Given — data-too-long violation: message has no "duplicate key" or "source_invoice_id"
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
            .thenThrow(new DataIntegrityViolationException(
                "value too long for type character varying(500)"));

        // When / Then — exception thrown immediately with accurate message
        ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException ex =
            assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
                () -> service.process("intake-123", "<xml>test</xml>",
                                      "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));
        assertInstanceOf(DataIntegrityViolationException.class, ex.getCause());
        assertTrue(ex.getMessage().contains("Constraint violation"),
            "Exception message should say 'Constraint violation', not duplicate-document");

        // FAILURE reply published with "Constraint violation:" prefix — not "Duplicate document:"
        verify(sagaReplyPort).publishFailure(
            eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE), eq("correlation-123"),
            contains("Constraint violation"));

        // Re-check MUST NOT happen — transactionManager.getTransaction never called
        verify(transactionManager, never()).getTransaction(any());

        // Domain event never published
        verify(eventPublisher, never()).publish(any());
    }

    /**
     * A DuplicateKeyException whose cause message does NOT contain the
     * source_invoice_id constraint name (e.g. duplicate invoice_number from a
     * different document) must be treated as a plain constraint violation:
     *  - no REQUIRES_NEW re-check
     *  - FAILURE reply with "Constraint violation:" prefix
     *  - processFailureCounter incremented, processRaceConditionResolvedCounter NOT
     */
    @Test
    void testProcessInvoiceForSagaDuplicateKeyOnNonIdempotentConstraint() throws Exception {
        // Given — invoice_number duplicate (different document, same number): constraint name
        // does NOT contain "uq_processed_tax_invoices_source_invoice_id"
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
        when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
        when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
            .thenThrow(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"idx_tax_invoice_number_unique\""));

        // When / Then — exception thrown immediately (no re-check)
        ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException ex =
            assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
                () -> service.process("intake-123", "<xml>test</xml>",
                                      "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));
        assertInstanceOf(DuplicateKeyException.class, ex.getCause());
        assertTrue(ex.getMessage().contains("Constraint violation"),
            "Exception message should say 'Constraint violation'");

        // FAILURE reply, not success
        verify(sagaReplyPort).publishFailure(
            eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE), eq("correlation-123"),
            contains("Constraint violation"));
        verify(sagaReplyPort, never()).publishSuccess(any(), any(), any());

        // Re-check MUST NOT happen — only one findBySourceInvoiceId call (the initial idempotency check)
        verify(invoiceRepository, times(1)).findBySourceInvoiceId(anyString());

        // Domain event never published
        verify(eventPublisher, never()).publish(any());
    }
}
