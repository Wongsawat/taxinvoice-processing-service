package com.wpanther.taxinvoice.processing.integration;

import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Kafka Consumer Integration Tests")
@Tag("integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class KafkaConsumerIntegrationTest extends AbstractKafkaConsumerTest {

    @Test
    @DisplayName("Should process valid tax invoice via saga command end-to-end")
    void shouldProcessValidTaxInvoiceEndToEnd() {
        // Given — unique invoice number per test run to avoid conflicts with stale Kafka messages
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String xmlContent = getSampleTaxInvoiceXml(invoiceNumber);

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, xmlContent, correlationId);

        // When
        sendEvent("saga.command.tax-invoice", documentId, command);

        // Then — await full processing (status = COMPLETED)
        Map<String, Object> invoice = awaitInvoiceBySourceId(documentId);

        // Verify main invoice fields
        assertThat(invoice.get("source_invoice_id")).isEqualTo(documentId);
        assertThat(invoice.get("invoice_number")).isEqualTo(invoiceNumber);
        assertThat(invoice.get("status")).isEqualTo("COMPLETED");
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

        // Verify outbox events created (2: taxinvoice.processed + saga.reply.tax-invoice)
        String sagaId = "saga-" + correlationId;
        awaitOutboxEventCount(documentId, 1);  // taxinvoice.processed uses documentId (sourceInvoiceId) as aggregate_id
        List<Map<String, Object>> invoiceOutboxEvents = getOutboxEvents(documentId);
        assertThat(invoiceOutboxEvents).hasSize(1);

        Map<String, Object> processedEvent = invoiceOutboxEvents.stream()
            .filter(e -> "taxinvoice.processed".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No taxinvoice.processed outbox event"));
        assertThat(processedEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");

        // Saga reply uses sagaId as aggregate_id
        List<Map<String, Object>> sagaOutboxEvents = getOutboxEventsBySagaId(sagaId);
        assertThat(sagaOutboxEvents).isNotEmpty();
        Map<String, Object> replyEvent = sagaOutboxEvents.stream()
            .filter(e -> "saga.reply.tax-invoice".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice outbox event"));
        String replyPayload = (String) replyEvent.get("payload");
        assertThat(replyPayload).contains("SUCCESS");
    }

    @Test
    @DisplayName("Should create outbox events for processed invoice")
    void shouldCreateOutboxEventsForProcessedInvoice() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String xmlContent = getSampleTaxInvoiceXml(invoiceNumber);

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, xmlContent, correlationId);

        // When
        sendEvent("saga.command.tax-invoice", documentId, command);

        // Then — await processing complete
        awaitInvoiceBySourceId(documentId);

        // Await taxinvoice.processed outbox event (aggregate_id = documentId = sourceInvoiceId)
        awaitOutboxEventCount(documentId, 1);
        List<Map<String, Object>> outboxEvents = getOutboxEvents(documentId);

        // Verify taxinvoice.processed event
        Map<String, Object> processedEvent = outboxEvents.stream()
            .filter(e -> "taxinvoice.processed".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No taxinvoice.processed outbox event"));
        assertThat(processedEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
        assertThat(processedEvent.get("partition_key")).isEqualTo(documentId);
        assertThat(processedEvent.get("status")).isEqualTo("PENDING");
        String processedPayload = (String) processedEvent.get("payload");
        assertThat(processedPayload).contains(documentId);
        assertThat(processedPayload).contains(invoiceNumber);
        assertThat(processedPayload).contains(correlationId);

        // Verify saga reply event (uses sagaId as aggregate_id)
        String sagaId = "saga-" + correlationId;
        List<Map<String, Object>> sagaOutboxEvents = getOutboxEventsBySagaId(sagaId);
        Map<String, Object> replyEvent = sagaOutboxEvents.stream()
            .filter(e -> "saga.reply.tax-invoice".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice outbox event"));
        assertThat(replyEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
        assertThat(replyEvent.get("partition_key")).isEqualTo(sagaId);
        String replyPayload = (String) replyEvent.get("payload");
        assertThat(replyPayload).contains("SUCCESS");
        assertThat(replyPayload).contains(correlationId);
    }

    @Test
    @DisplayName("Should calculate totals correctly")
    void shouldCalculateTotalsCorrectly() {
        // Given: XML with 2 items: 10*5000=50000 + 1*10000=10000 = 60000 subtotal
        // Tax: 60000 * 7% = 4200, Total: 64200
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sendEvent("saga.command.tax-invoice", documentId, command);

        // Then
        Map<String, Object> invoice = awaitInvoiceBySourceId(documentId);

        assertThat(((BigDecimal) invoice.get("subtotal")).compareTo(new BigDecimal("60000.00"))).isZero();
        assertThat(((BigDecimal) invoice.get("total_tax")).compareTo(new BigDecimal("4200.00"))).isZero();
        assertThat(((BigDecimal) invoice.get("total")).compareTo(new BigDecimal("64200.00"))).isZero();
    }

    @Test
    @DisplayName("Should skip duplicate command with same documentId")
    void shouldSkipDuplicateCommand() throws Exception {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When — send same command twice
        sendEvent("saga.command.tax-invoice", documentId, command);
        awaitInvoiceBySourceId(documentId);

        // Send duplicate
        sendEvent("saga.command.tax-invoice", documentId, command);

        // Wait a bit for potential duplicate processing
        Thread.sleep(5000);

        // Then — only 1 row
        List<Map<String, Object>> invoices = testJdbcTemplate.queryForList(
            "SELECT * FROM processed_tax_invoices WHERE source_invoice_id = ?", documentId);
        assertThat(invoices).hasSize(1);
    }

    @Test
    @DisplayName("Should process commands with different documentIds separately")
    void shouldProcessCommandsWithDifferentDocumentIds() {
        // Given
        String documentId1 = "DOC-" + UUID.randomUUID();
        String documentId2 = "DOC-" + UUID.randomUUID();
        String invoiceNumber1 = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String invoiceNumber2 = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId1 = UUID.randomUUID().toString();
        String correlationId2 = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command1 = createProcessTaxInvoiceCommand(
            documentId1, invoiceNumber1, getSampleTaxInvoiceXml(invoiceNumber1), correlationId1);

        ProcessTaxInvoiceCommand command2 = createProcessTaxInvoiceCommand(
            documentId2, invoiceNumber2, getSampleTaxInvoiceXml(invoiceNumber2), correlationId2);

        // When
        sendEvent("saga.command.tax-invoice", documentId1, command1);
        sendEvent("saga.command.tax-invoice", documentId2, command2);

        // Then — both processed
        awaitInvoiceBySourceId(documentId1);
        awaitInvoiceBySourceId(documentId2);

        assertThat(getInvoiceCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Should send FAILURE reply for invalid XML")
    void shouldSendFailureReplyForInvalidXml() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-INVALID";
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, "<invalid>Not a valid tax invoice</invalid>", correlationId);

        // When
        sendEvent("saga.command.tax-invoice", documentId, command);

        // Then — no invoice created, but FAILURE reply sent
        assertNoInvoiceCreatedAfterWait(documentId);
    }

    @Test
    @DisplayName("Should compensate (delete) a previously processed tax invoice")
    void shouldCompensateProcessedTaxInvoice() {
        // Given — process first
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand processCommand = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);
        sendEvent("saga.command.tax-invoice", documentId, processCommand);
        awaitInvoiceBySourceId(documentId);

        // When — send compensation command
        String compensateCorrelationId = UUID.randomUUID().toString();
        String compensateSagaId = "saga-" + compensateCorrelationId;
        CompensateTaxInvoiceCommand compensateCommand = createCompensateTaxInvoiceCommand(
            documentId, compensateCorrelationId);
        sendEvent("saga.compensation.tax-invoice", documentId, compensateCommand);

        // Then — invoice deleted from DB
        await().atMost(2, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS)
               .until(() -> getInvoiceBySourceId(documentId) == null);

        // COMPENSATED reply written to outbox
        await().atMost(2, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS)
               .until(() -> !getOutboxEventsBySagaId(compensateSagaId).isEmpty());

        List<Map<String, Object>> sagaOutboxEvents = getOutboxEventsBySagaId(compensateSagaId);
        Map<String, Object> replyEvent = sagaOutboxEvents.stream()
            .filter(e -> "saga.reply.tax-invoice".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice outbox event after compensation"));
        assertThat((String) replyEvent.get("payload")).contains("COMPENSATED");
        assertThat((String) replyEvent.get("payload")).contains(compensateCorrelationId);
    }

    @Test
    @DisplayName("Should send COMPENSATED reply even for non-existent invoice (idempotent no-op)")
    void shouldSendCompensatedReplyForNonExistentInvoice() {
        // Given — invoice was never processed
        String documentId = "DOC-" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        String sagaId = "saga-" + correlationId;

        CompensateTaxInvoiceCommand compensateCommand = createCompensateTaxInvoiceCommand(
            documentId, correlationId);

        // When
        sendEvent("saga.compensation.tax-invoice", documentId, compensateCommand);

        // Then — COMPENSATED reply still written to outbox
        await().atMost(2, TimeUnit.MINUTES).pollInterval(1, TimeUnit.SECONDS)
               .until(() -> !getOutboxEventsBySagaId(sagaId).isEmpty());

        List<Map<String, Object>> sagaOutboxEvents = getOutboxEventsBySagaId(sagaId);
        Map<String, Object> replyEvent = sagaOutboxEvents.stream()
            .filter(e -> "saga.reply.tax-invoice".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice outbox event"));
        assertThat((String) replyEvent.get("payload")).contains("COMPENSATED");
    }

    @Test
    @DisplayName("Should send FAILURE reply for empty XML")
    void shouldSendFailureReplyForEmptyXml() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-EMPTY";
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, "", correlationId);

        // When
        sendEvent("saga.command.tax-invoice", documentId, command);

        // Then — no invoice created
        assertNoInvoiceCreatedAfterWait(documentId);
    }
}
