package com.wpanther.taxinvoice.processing.domain.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for XmlSigningRequestedEvent
 */
class XmlSigningRequestedEventTest {

    @Test
    void testCreateEvent() {
        // Given
        String invoiceId = "taxinvoice-123";
        String invoiceNumber = "TXN-001";
        String xmlContent = "<xml>tax invoice content</xml>";
        String invoiceDataJson = "{\"seller\":\"Acme Corp\",\"buyer\":\"Customer Co\"}";
        String correlationId = "correlation-123";
        String documentType = "TAX_INVOICE";

        // When
        XmlSigningRequestedEvent event = new XmlSigningRequestedEvent(
            invoiceId, invoiceNumber, xmlContent, invoiceDataJson, correlationId, documentType
        );

        // Then
        assertNotNull(event);
        assertEquals(invoiceId, event.getInvoiceId());
        assertEquals(invoiceNumber, event.getInvoiceNumber());
        assertEquals(xmlContent, event.getXmlContent());
        assertEquals(invoiceDataJson, event.getInvoiceDataJson());
        assertEquals(correlationId, event.getCorrelationId());
        assertEquals(documentType, event.getDocumentType());
        assertEquals("xml.signing.requested", event.getEventType());
        assertNotNull(event.getEventId());
        assertNotNull(event.getOccurredAt());
    }

    @Test
    void testDocumentType() {
        // Given
        XmlSigningRequestedEvent event = new XmlSigningRequestedEvent(
            "taxinvoice-123",
            "TXN-001",
            "<xml>tax invoice content</xml>",
            "{\"seller\":\"Acme Corp\",\"buyer\":\"Customer Co\"}",
            "correlation-123",
            "TAX_INVOICE"
        );

        // When/Then
        assertEquals("TAX_INVOICE", event.getDocumentType());
    }

    @Test
    void testJsonSerialization() throws Exception {
        // Given
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        XmlSigningRequestedEvent event = new XmlSigningRequestedEvent(
            "taxinvoice-123",
            "TXN-001",
            "<xml>tax invoice content</xml>",
            "{\"seller\":\"Acme Corp\",\"buyer\":\"Customer Co\"}",
            "correlation-123",
            "TAX_INVOICE"
        );

        // When
        String json = objectMapper.writeValueAsString(event);
        XmlSigningRequestedEvent deserialized = objectMapper.readValue(json, XmlSigningRequestedEvent.class);

        // Then
        assertEquals(event.getEventId(), deserialized.getEventId());
        assertEquals(event.getInvoiceId(), deserialized.getInvoiceId());
        assertEquals(event.getInvoiceNumber(), deserialized.getInvoiceNumber());
        assertEquals(event.getXmlContent(), deserialized.getXmlContent());
        assertEquals(event.getInvoiceDataJson(), deserialized.getInvoiceDataJson());
        assertEquals(event.getCorrelationId(), deserialized.getCorrelationId());
        assertEquals(event.getDocumentType(), deserialized.getDocumentType());
    }
}
