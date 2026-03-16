package com.wpanther.taxinvoice.processing.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.processing.application.port.in.ProcessTaxInvoiceUseCase;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.processing.application.port.out.TaxInvoiceEventPublishingPort;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    private TaxInvoiceProcessingService service;

    private ProcessedTaxInvoice validInvoice;

    @BeforeEach
    void setUp() {
        service = new TaxInvoiceProcessingService(
            invoiceRepository,
            parserService,
            eventPublisher,
            sagaReplyPort,
            new SimpleMeterRegistry()
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
            Money.of(1000.00, "THB"),
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
        verify(eventPublisher).publish(any(TaxInvoiceProcessedEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");
    }

    @Test
    void testProcessInvoiceForSagaAlreadyProcessed() throws Exception {
        // Given
        when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.of(validInvoice));

        // When
        service.process("intake-123", "<xml>test</xml>", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");

        // Then - idempotent: should return early without processing
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(parserService, never()).parse(anyString(), anyString());
        verify(invoiceRepository, never()).save(any(ProcessedTaxInvoice.class));
        verify(eventPublisher, never()).publish(any());
        // For idempotent case, saga reply is still published
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123");
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
        ArgumentCaptor<TaxInvoiceProcessedEvent> eventCaptor =
            ArgumentCaptor.forClass(TaxInvoiceProcessedEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());

        TaxInvoiceProcessedEvent processedEvent = eventCaptor.getValue();
        assertEquals("TXN-001", processedEvent.getInvoiceNumber());
        assertEquals("THB", processedEvent.getCurrency());
        assertEquals("correlation-123", processedEvent.getCorrelationId());
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
    void testFindByIdValid() {
        // Given
        TaxInvoiceId id = TaxInvoiceId.generate();
        when(invoiceRepository.findById(any(TaxInvoiceId.class))).thenReturn(Optional.of(validInvoice));

        // When
        Optional<ProcessedTaxInvoice> result = service.findById(id.toString());

        // Then
        assertTrue(result.isPresent());
        assertEquals(validInvoice, result.get());
        verify(invoiceRepository).findById(any(TaxInvoiceId.class));
    }

    @Test
    void testFindByIdInvalidFormat() {
        // Given
        String invalidId = "not-a-uuid";

        // When
        Optional<ProcessedTaxInvoice> result = service.findById(invalidId);

        // Then
        assertFalse(result.isPresent());
        verify(invoiceRepository, never()).findById(any(TaxInvoiceId.class));
    }

    @Test
    void testFindByIdNotFound() {
        // Given
        TaxInvoiceId id = TaxInvoiceId.generate();
        when(invoiceRepository.findById(any(TaxInvoiceId.class))).thenReturn(Optional.empty());

        // When
        Optional<ProcessedTaxInvoice> result = service.findById(id.toString());

        // Then
        assertFalse(result.isPresent());
        verify(invoiceRepository).findById(any(TaxInvoiceId.class));
    }

    @Test
    void testFindByStatus() {
        // Given
        ProcessingStatus status = ProcessingStatus.COMPLETED;
        List<ProcessedTaxInvoice> invoices = List.of(validInvoice);
        when(invoiceRepository.findByStatus(status)).thenReturn(invoices);

        // When
        List<ProcessedTaxInvoice> result = service.findByStatus(status);

        // Then
        assertEquals(1, result.size());
        assertEquals(validInvoice, result.get(0));
        verify(invoiceRepository).findByStatus(status);
    }

    @Test
    void testFindByStatusEmpty() {
        // Given
        ProcessingStatus status = ProcessingStatus.FAILED;
        when(invoiceRepository.findByStatus(status)).thenReturn(List.of());

        // When
        List<ProcessedTaxInvoice> result = service.findByStatus(status);

        // Then
        assertTrue(result.isEmpty());
        verify(invoiceRepository).findByStatus(status);
    }

    @Test
    void testCompensateDeletesExistingInvoice() {
        // Given
        TaxInvoiceId id = TaxInvoiceId.generate();
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
            Money.of(1000.00, "THB"),
            new BigDecimal("7.00")
        );
        validInvoice = ProcessedTaxInvoice.builder()
            .id(id)
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
        when(invoiceRepository.findBySourceInvoiceId("intake-123")).thenReturn(Optional.of(validInvoice));

        // When
        service.compensate("intake-123", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        // Then
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(invoiceRepository).deleteById(id);
        verify(sagaReplyPort).publishCompensated("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");
    }

    @Test
    void testCompensateNotFound() {
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

        // When
        service.compensate("intake-123", "saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        // Then
        verify(invoiceRepository).findBySourceInvoiceId("intake-123");
        verify(invoiceRepository).deleteById(any());
        verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE), eq("corr-1"), contains("Compensation failed"));
    }
}
