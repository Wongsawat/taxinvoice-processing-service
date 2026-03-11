package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.saga.infrastructure.outbox.OutboxService;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceProcessedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaxInvoiceEventPublisher
 */
@ExtendWith(MockitoExtension.class)
class TaxInvoiceEventPublisherTest {

    @Mock
    private OutboxService outboxService;

    @Mock
    private HeaderSerializer headerSerializer;

    private TaxInvoiceEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = new TaxInvoiceEventPublisher(outboxService, headerSerializer);
    }

    @Test
    void testPublishTaxInvoiceProcessedSuccess() throws Exception {
        // Given
        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            "invoice-123",
            "TXN-001",
            new java.math.BigDecimal("10000.00"),
            "THB",
            "correlation-123"
        );

        when(headerSerializer.toJson(any())).thenReturn("{\"correlationId\":\"correlation-123\",\"invoiceNumber\":\"TXN-001\"}");

        // When
        eventPublisher.publish(event);

        // Then
        verify(outboxService).saveWithRouting(
            eq(event),
            eq("ProcessedTaxInvoice"),
            eq("invoice-123"),
            eq("taxinvoice.processed"),
            eq("invoice-123"),
            eq("{\"correlationId\":\"correlation-123\",\"invoiceNumber\":\"TXN-001\"}")
        );
    }

    @Test
    void testPublishTaxInvoiceProcessedHeaderContent() throws Exception {
        // Given
        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            "invoice-123",
            "TXN-001",
            new java.math.BigDecimal("10000.00"),
            "THB",
            "correlation-123"
        );

        when(headerSerializer.toJson(any())).thenReturn("{\"correlationId\":\"correlation-123\",\"invoiceNumber\":\"TXN-001\"}");

        // When
        eventPublisher.publish(event);

        // Then
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(
            any(),
            any(),
            any(),
            any(),
            any(),
            headersCaptor.capture()
        );

        String headers = headersCaptor.getValue();
        assertTrue(headers.contains("correlation-123"));
        assertTrue(headers.contains("TXN-001"));
    }

    @Test
    void testToJsonError() throws Exception {
        // Given
        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            "invoice-123",
            "TXN-001",
            new java.math.BigDecimal("10000.00"),
            "THB",
            "correlation-123"
        );

        when(headerSerializer.toJson(any())).thenReturn(null);

        // When
        eventPublisher.publish(event);

        // Then
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(
            any(),
            any(),
            any(),
            any(),
            any(),
            headersCaptor.capture()
        );

        assertNull(headersCaptor.getValue());
    }

    @Test
    void testPublishUsesCorrectTopic() throws Exception {
        // Given
        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            "invoice-123",
            "TXN-001",
            new java.math.BigDecimal("10000.00"),
            "THB",
            "correlation-123"
        );

        when(headerSerializer.toJson(any())).thenReturn("{}");

        // When
        eventPublisher.publish(event);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), topicCaptor.capture(), any(), any());
        assertEquals("taxinvoice.processed", topicCaptor.getValue());
    }

    @Test
    void testPublishUsesInvoiceIdAsPartitionKey() throws Exception {
        // Given
        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            "invoice-456",
            "TXN-002",
            new java.math.BigDecimal("5000.00"),
            "THB",
            "correlation-456"
        );

        when(headerSerializer.toJson(any())).thenReturn("{}");

        // When
        eventPublisher.publish(event);

        // Then
        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), any(), partitionKeyCaptor.capture(), any());
        assertEquals("invoice-456", partitionKeyCaptor.getValue());
    }
}
