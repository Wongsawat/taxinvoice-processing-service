package com.wpanther.taxinvoice.processing.domain.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.processing.application.dto.event.TaxInvoiceProcessedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaxInvoiceProcessedEvent
 */
class TaxInvoiceProcessedEventTest {

    @Test
    void testCreateEvent() {
        // Given
        String documentId = "taxinvoice-123";
        String documentNumber = "TXN-001";
        BigDecimal total = new BigDecimal("10000.00");
        String currency = "THB";
        String sagaId = "saga-123";
        String correlationId = "correlation-123";

        // When
        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            documentId, documentNumber, total, currency, sagaId, correlationId
        );

        // Then
        assertNotNull(event);
        assertEquals(documentId, event.getDocumentId());
        assertEquals(documentNumber, event.getDocumentNumber());
        assertEquals(total, event.getTotal());
        assertEquals(currency, event.getCurrency());
        assertEquals(sagaId, event.getSagaId());
        assertEquals(correlationId, event.getCorrelationId());
        assertEquals("taxinvoice.processed", event.getEventType());
        assertNotNull(event.getEventId());
        assertNotNull(event.getOccurredAt());
    }

    @Test
    void testJsonSerialization() throws Exception {
        // Given
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        TaxInvoiceProcessedEvent event = new TaxInvoiceProcessedEvent(
            "taxinvoice-123",
            "TXN-001",
            new BigDecimal("10000.00"),
            "THB",
            "saga-123",
            "correlation-123"
        );

        // When
        String json = objectMapper.writeValueAsString(event);
        TaxInvoiceProcessedEvent deserialized = objectMapper.readValue(json, TaxInvoiceProcessedEvent.class);

        // Then
        assertEquals(event.getEventId(), deserialized.getEventId());
        assertEquals(event.getDocumentId(), deserialized.getDocumentId());
        assertEquals(event.getDocumentNumber(), deserialized.getDocumentNumber());
        assertEquals(event.getTotal(), deserialized.getTotal());
        assertEquals(event.getCurrency(), deserialized.getCurrency());
        assertEquals(event.getCorrelationId(), deserialized.getCorrelationId());
    }
}
