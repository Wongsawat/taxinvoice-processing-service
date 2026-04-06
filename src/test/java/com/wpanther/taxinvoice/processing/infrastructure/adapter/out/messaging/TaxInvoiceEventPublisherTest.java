package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.saga.infrastructure.outbox.OutboxService;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceProcessedDomainEvent;
import com.wpanther.taxinvoice.processing.domain.model.Money;
import java.math.BigDecimal;

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
        eventPublisher = new TaxInvoiceEventPublisher(outboxService, headerSerializer, "taxinvoice.processed");
    }

    @Test
    void testPublishTaxInvoiceProcessedSuccess() throws Exception {
        // Given
        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            "550e8400-e29b-41d4-a716-446655440000",
            "TXN-001",
            Money.of(new BigDecimal("10000.00"), "THB"),
            "saga-123",
            "correlation-123",
            java.time.Instant.now()
        );

        when(headerSerializer.toJson(any())).thenReturn("{\"correlationId\":\"correlation-123\",\"invoiceNumber\":\"TXN-001\"}");

        // When
        eventPublisher.publish(event);

        // Then
        // Note: adapter transforms domain event to Kafka event, so we verify with any()
        verify(outboxService).saveWithRouting(
            any(com.wpanther.taxinvoice.processing.application.dto.event.TaxInvoiceProcessedEvent.class),
            eq("ProcessedTaxInvoice"),
            eq("550e8400-e29b-41d4-a716-446655440000"),
            eq("taxinvoice.processed"),
            eq("550e8400-e29b-41d4-a716-446655440000"),
            eq("{\"correlationId\":\"correlation-123\",\"invoiceNumber\":\"TXN-001\"}")
        );
    }

    @Test
    void testPublishTaxInvoiceProcessedHeaderContent() throws Exception {
        // Given
        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            "550e8400-e29b-41d4-a716-446655440001",
            "TXN-001",
            Money.of(new BigDecimal("10000.00"), "THB"),
            "saga-123",
            "correlation-123",
            java.time.Instant.now()
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
        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            "550e8400-e29b-41d4-a716-446655440002",
            "TXN-001",
            Money.of(new BigDecimal("10000.00"), "THB"),
            "saga-123",
            "correlation-123",
            java.time.Instant.now()
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
        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            "550e8400-e29b-41d4-a716-446655440003",
            "TXN-001",
            Money.of(new BigDecimal("10000.00"), "THB"),
            "saga-123",
            "correlation-123",
            java.time.Instant.now()
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
        TaxInvoiceProcessedDomainEvent event = new TaxInvoiceProcessedDomainEvent(
            "550e8400-e29b-41d4-a716-446655440004",
            "TXN-002",
            Money.of(new BigDecimal("5000.00"), "THB"),
            "saga-456",
            "correlation-456",
            java.time.Instant.now()
        );

        when(headerSerializer.toJson(any())).thenReturn("{}");

        // When
        eventPublisher.publish(event);

        // Then
        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), any(), partitionKeyCaptor.capture(), any());
        assertEquals("550e8400-e29b-41d4-a716-446655440004", partitionKeyCaptor.getValue());
    }
}
