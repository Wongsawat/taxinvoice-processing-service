package com.wpanther.taxinvoice.processing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceReceivedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

@DisplayName("CDC Integration Tests")
@Tag("integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class TaxInvoiceCdcIntegrationTest extends AbstractCdcIntegrationTest {

    // ========== Database Write Tests ==========

    @Test
    @DisplayName("Should persist processed tax invoice to database")
    void shouldPersistProcessedTaxInvoice() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When — call service directly (no Camel consumer in CDC tests)
        processingService.processInvoiceReceived(event);

        // Then
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        assertThat(invoice).isNotNull();
        assertThat(invoice.get("invoice_number")).isEqualTo(invoiceNumber);
        assertThat(invoice.get("status")).isEqualTo("PDF_REQUESTED");
        assertThat(invoice.get("currency")).isEqualTo("THB");
    }

    @Test
    @DisplayName("Should create outbox events entries with correct metadata")
    void shouldCreateOutboxEntries() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        processingService.processInvoiceReceived(event);

        // Then
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        List<Map<String, Object>> outboxEvents = getOutboxEvents(invoiceId);
        assertThat(outboxEvents).hasSize(2);

        // All outbox events should have correct aggregate metadata
        for (Map<String, Object> outboxEvent : outboxEvents) {
            assertThat(outboxEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
            assertThat(outboxEvent.get("aggregate_id")).isEqualTo(invoiceId);
            assertThat(outboxEvent.get("status")).isEqualTo("PENDING");
        }
    }

    // ========== Outbox Pattern Tests ==========

    @Test
    @DisplayName("Should write TaxInvoiceProcessedEvent to outbox with correct topic")
    void shouldWriteProcessedEventToOutbox() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        processingService.processInvoiceReceived(event);

        // Then
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        List<Map<String, Object>> outboxEvents = getOutboxEvents(invoiceId);
        Map<String, Object> processedEvent = outboxEvents.stream()
            .filter(e -> "taxinvoice.processed".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No taxinvoice.processed outbox event"));

        assertThat(processedEvent.get("partition_key")).isEqualTo(invoiceId);
        String payload = (String) processedEvent.get("payload");
        assertThat(payload).contains(invoiceId);
        assertThat(payload).contains(invoiceNumber);
        assertThat(payload).contains(correlationId);
    }

    @Test
    @DisplayName("Should write XmlSigningRequestedEvent with documentType=TAX_INVOICE")
    void shouldWriteSigningEventWithDocumentType() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        processingService.processInvoiceReceived(event);

        // Then
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        List<Map<String, Object>> outboxEvents = getOutboxEvents(invoiceId);
        Map<String, Object> signingEvent = outboxEvents.stream()
            .filter(e -> "xml.signing.requested".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No xml.signing.requested outbox event"));

        String payload = (String) signingEvent.get("payload");
        assertThat(payload).contains("\"documentType\":\"TAX_INVOICE\"");
        assertThat(payload).contains(correlationId);
    }

    // ========== CDC Flow Tests ==========

    @Test
    @DisplayName("Should publish TaxInvoiceProcessedEvent to Kafka via CDC")
    void shouldPublishProcessedEventViaCdc() throws Exception {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        processingService.processInvoiceReceived(event);
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        // Then — await message on taxinvoice.processed topic
        await().atMost(2, MINUTES).pollInterval(2, SECONDS)
               .until(() -> hasMessageOnTopic("taxinvoice.processed", invoiceId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("taxinvoice.processed", invoiceId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = parseJson(messages.get(0).value());
        assertThat(payload.has("invoiceNumber")).isTrue();
        assertThat(payload.get("invoiceNumber").asText()).isEqualTo(invoiceNumber);
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("Should publish XmlSigningRequestedEvent to Kafka via CDC with TAX_INVOICE type")
    void shouldPublishSigningEventViaCdc() throws Exception {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        TaxInvoiceReceivedEvent event = createTaxInvoiceReceivedEvent(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        processingService.processInvoiceReceived(event);
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        // Then — await message on xml.signing.requested topic
        await().atMost(2, MINUTES).pollInterval(2, SECONDS)
               .until(() -> hasMessageOnTopic("xml.signing.requested", invoiceId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("xml.signing.requested", invoiceId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = parseJson(messages.get(0).value());
        assertThat(payload.get("documentType").asText()).isEqualTo("TAX_INVOICE");
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(payload.has("xmlContent")).isTrue();
        assertThat(payload.has("invoiceDataJson")).isTrue();
    }
}
