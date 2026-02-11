package com.wpanther.taxinvoice.processing.integration;

import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceReceivedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kafka Consumer Integration Tests")
@Tag("integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class KafkaConsumerIntegrationTest extends AbstractKafkaConsumerTest {

    @Test
    @DisplayName("Should process valid tax invoice end-to-end")
    void shouldProcessValidTaxInvoiceEndToEnd() {
        // Given — unique invoice number per test run to avoid conflicts with stale Kafka messages
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String xmlContent = getSampleTaxInvoiceXml(invoiceNumber);

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, xmlContent, correlationId);

        // When
        sendEvent("document.received.tax-invoice", documentId, event);

        // Then — await full processing (status = PDF_REQUESTED)
        Map<String, Object> invoice = awaitInvoiceBySourceId(documentId);

        // Verify main invoice fields
        assertThat(invoice.get("source_invoice_id")).isEqualTo(documentId);
        assertThat(invoice.get("invoice_number")).isEqualTo(invoiceNumber);
        assertThat(invoice.get("status")).isEqualTo("PDF_REQUESTED");
        assertThat(invoice.get("currency")).isEqualTo("THB");
        assertThat(invoice.get("original_xml")).isEqualTo(xmlContent);
        assertThat(invoice.get("issue_date").toString()).contains("2025-01-15");
        assertThat(invoice.get("due_date").toString()).contains("2025-02-14");

        String invoiceId = invoice.get("id").toString();

        // Verify parties
        List<Map<String, Object>> parties = getParties(invoiceId);
        assertThat(parties).hasSize(2);

        Map<String, Object> seller = parties.stream()
            .filter(p -> "SELLER".equals(p.get("party_type")))
            .findFirst().orElseThrow();
        Map<String, Object> buyer = parties.stream()
            .filter(p -> "BUYER".equals(p.get("party_type")))
            .findFirst().orElseThrow();

        assertThat(seller.get("name")).isEqualTo("Acme Corporation Ltd.");
        assertThat(seller.get("tax_id")).isEqualTo("1234567890123");
        assertThat(seller.get("tax_id_scheme")).isEqualTo("VAT");
        assertThat(seller.get("city")).isEqualTo("Bangkok");
        assertThat(seller.get("country")).isEqualTo("TH");

        assertThat(buyer.get("name")).isEqualTo("Customer Company Ltd.");
        assertThat(buyer.get("tax_id")).isEqualTo("9876543210987");
        assertThat(buyer.get("city")).isEqualTo("Chiang Mai");
        assertThat(buyer.get("country")).isEqualTo("TH");

        // Verify line items
        List<Map<String, Object>> lineItems = getLineItems(invoiceId);
        assertThat(lineItems).hasSize(2);

        Map<String, Object> item1 = lineItems.get(0);
        assertThat(item1.get("line_number")).isEqualTo(1);
        assertThat(item1.get("description")).isEqualTo("Professional Services - Consulting");
        assertThat(item1.get("quantity")).isEqualTo(10);
        assertThat(((BigDecimal) item1.get("unit_price")).compareTo(new BigDecimal("5000.00"))).isZero();
        assertThat(((BigDecimal) item1.get("tax_rate")).compareTo(new BigDecimal("7.00"))).isZero();

        Map<String, Object> item2 = lineItems.get(1);
        assertThat(item2.get("line_number")).isEqualTo(2);
        assertThat(item2.get("description")).isEqualTo("Software License");
        assertThat(item2.get("quantity")).isEqualTo(1);
        assertThat(((BigDecimal) item2.get("unit_price")).compareTo(new BigDecimal("10000.00"))).isZero();

        // Verify totals: 10*5000 + 1*10000 = 60000 subtotal, 7% tax = 4200, total = 64200
        assertThat(((BigDecimal) invoice.get("subtotal")).compareTo(new BigDecimal("60000.00"))).isZero();
        assertThat(((BigDecimal) invoice.get("total_tax")).compareTo(new BigDecimal("4200.00"))).isZero();
        assertThat(((BigDecimal) invoice.get("total")).compareTo(new BigDecimal("64200.00"))).isZero();

        // Verify outbox events created (2: taxinvoice.processed + xml.signing.requested)
        awaitOutboxEventCount(invoiceId, 2);
        List<Map<String, Object>> outboxEvents = getOutboxEvents(invoiceId);
        assertThat(outboxEvents).hasSize(2);

        Map<String, Object> processedEvent = outboxEvents.stream()
            .filter(e -> "taxinvoice.processed".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No taxinvoice.processed outbox event"));
        assertThat(processedEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");

        Map<String, Object> signingEvent = outboxEvents.stream()
            .filter(e -> "xml.signing.requested".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No xml.signing.requested outbox event"));
        String signingPayload = (String) signingEvent.get("payload");
        assertThat(signingPayload).contains("TAX_INVOICE");
    }

    @Test
    @DisplayName("Should create outbox events for processed invoice")
    void shouldCreateOutboxEventsForProcessedInvoice() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String xmlContent = getSampleTaxInvoiceXml(invoiceNumber);

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, xmlContent, correlationId);

        // When
        sendEvent("document.received.tax-invoice", documentId, event);

        // Then — await processing complete
        Map<String, Object> invoice = awaitInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        // Await 2 outbox events
        awaitOutboxEventCount(invoiceId, 2);
        List<Map<String, Object>> outboxEvents = getOutboxEvents(invoiceId);

        // Verify taxinvoice.processed event
        Map<String, Object> processedEvent = outboxEvents.stream()
            .filter(e -> "taxinvoice.processed".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No taxinvoice.processed outbox event"));
        assertThat(processedEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
        assertThat(processedEvent.get("partition_key")).isEqualTo(invoiceId);
        assertThat(processedEvent.get("status")).isEqualTo("PENDING");
        String processedPayload = (String) processedEvent.get("payload");
        assertThat(processedPayload).contains(invoiceId);
        assertThat(processedPayload).contains(invoiceNumber);
        assertThat(processedPayload).contains(correlationId);

        // Verify xml.signing.requested event
        Map<String, Object> signingEvent = outboxEvents.stream()
            .filter(e -> "xml.signing.requested".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No xml.signing.requested outbox event"));
        assertThat(signingEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
        assertThat(signingEvent.get("partition_key")).isEqualTo(invoiceId);
        assertThat(signingEvent.get("status")).isEqualTo("PENDING");
        String signingPayload = (String) signingEvent.get("payload");
        assertThat(signingPayload).contains(invoiceId);
        assertThat(signingPayload).contains("TAX_INVOICE");
        assertThat(signingPayload).contains(correlationId);
    }

    @Test
    @DisplayName("Should calculate totals correctly")
    void shouldCalculateTotalsCorrectly() {
        // Given: XML with 2 items: 10*5000=50000 + 1*10000=10000 = 60000 subtotal
        // Tax: 60000 * 7% = 4200, Total: 64200
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sendEvent("document.received.tax-invoice", documentId, event);

        // Then
        Map<String, Object> invoice = awaitInvoiceBySourceId(documentId);

        assertThat(((BigDecimal) invoice.get("subtotal")).compareTo(new BigDecimal("60000.00"))).isZero();
        assertThat(((BigDecimal) invoice.get("total_tax")).compareTo(new BigDecimal("4200.00"))).isZero();
        assertThat(((BigDecimal) invoice.get("total")).compareTo(new BigDecimal("64200.00"))).isZero();
    }

    @Test
    @DisplayName("Should skip duplicate event with same documentId")
    void shouldSkipDuplicateEvent() throws Exception {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When — send same event twice
        sendEvent("document.received.tax-invoice", documentId, event);
        awaitInvoiceBySourceId(documentId);

        // Send duplicate
        sendEvent("document.received.tax-invoice", documentId, event);

        // Wait a bit for potential duplicate processing
        Thread.sleep(5000);

        // Then — only 1 row
        List<Map<String, Object>> invoices = testJdbcTemplate.queryForList(
            "SELECT * FROM processed_tax_invoices WHERE source_invoice_id = ?", documentId);
        assertThat(invoices).hasSize(1);
    }

    @Test
    @DisplayName("Should process events with different documentIds separately")
    void shouldProcessEventsWithDifferentDocumentIds() {
        // Given
        String documentId1 = "DOC-" + UUID.randomUUID();
        String documentId2 = "DOC-" + UUID.randomUUID();
        String invoiceNumber1 = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String invoiceNumber2 = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId1 = UUID.randomUUID().toString();
        String correlationId2 = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event1 = createTaxInvoiceReceivedEvent(
            documentId1, invoiceNumber1, getSampleTaxInvoiceXml(invoiceNumber1), correlationId1);

        TaxInvoiceReceivedEvent event2 = createTaxInvoiceReceivedEvent(
            documentId2, invoiceNumber2, getSampleTaxInvoiceXml(invoiceNumber2), correlationId2);

        // When
        sendEvent("document.received.tax-invoice", documentId1, event1);
        sendEvent("document.received.tax-invoice", documentId2, event2);

        // Then — both processed
        awaitInvoiceBySourceId(documentId1);
        awaitInvoiceBySourceId(documentId2);

        assertThat(getInvoiceCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Should not persist for invalid XML")
    void shouldNotPersistForInvalidXml() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-INVALID";
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, "<invalid>Not a valid tax invoice</invalid>", correlationId);

        // When
        sendEvent("document.received.tax-invoice", documentId, event);

        // Then — wait and verify no invoice was created
        // Note: Exception caught silently by processInvoiceReceived — no DLQ
        assertNoInvoiceCreatedAfterWait(documentId);
    }

    @Test
    @DisplayName("Should not persist for empty XML")
    void shouldNotPersistForEmptyXml() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-EMPTY";
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, "", correlationId);

        // When
        sendEvent("document.received.tax-invoice", documentId, event);

        // Then
        // Note: Exception caught silently by processInvoiceReceived — no DLQ
        assertNoInvoiceCreatedAfterWait(documentId);
    }
}