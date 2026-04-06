package com.wpanther.taxinvoice.processing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
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

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When — call saga handler directly (no Camel consumer in CDC tests)
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        assertThat(invoice).isNotNull();
        assertThat(invoice.get("invoice_number")).isEqualTo(invoiceNumber);
        assertThat(invoice.get("status")).isEqualTo("COMPLETED");
        assertThat(invoice.get("currency")).isEqualTo("THB");
    }

    @Test
    @DisplayName("Should create outbox event entries with correct metadata")
    void shouldCreateOutboxEntries() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String sagaId = "saga-" + correlationId;

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then — verify taxinvoice.processed outbox event
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        List<Map<String, Object>> invoiceOutboxEvents = getOutboxEvents(invoiceId);
        assertThat(invoiceOutboxEvents).hasSize(1);

        Map<String, Object> processedEvent = invoiceOutboxEvents.get(0);
        assertThat(processedEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
        assertThat(processedEvent.get("aggregate_id")).isEqualTo(invoiceId);
        assertThat(processedEvent.get("status")).isEqualTo("PENDING");
        assertThat(processedEvent.get("topic")).isEqualTo("taxinvoice.processed");

        // Verify saga reply outbox event (uses sagaId as aggregate_id)
        List<Map<String, Object>> sagaOutboxEvents = getOutboxEvents(sagaId);
        assertThat(sagaOutboxEvents).hasSize(1);

        Map<String, Object> replyEvent = sagaOutboxEvents.get(0);
        assertThat(replyEvent.get("aggregate_type")).isEqualTo("ProcessedTaxInvoice");
        assertThat(replyEvent.get("topic")).isEqualTo("saga.reply.tax-invoice");
        assertThat(replyEvent.get("status")).isEqualTo("PENDING");
    }

    // ========== Outbox Pattern Tests ==========

    @Test
    @DisplayName("Should write TaxInvoiceProcessedEvent to outbox with correct topic")
    void shouldWriteProcessedEventToOutbox() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sagaCommandHandler.handleProcessCommand(command);

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
    @DisplayName("Should write saga SUCCESS reply to outbox")
    void shouldWriteSagaSuccessReplyToOutbox() {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String sagaId = "saga-" + correlationId;

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        List<Map<String, Object>> sagaOutboxEvents = getOutboxEvents(sagaId);
        Map<String, Object> replyEvent = sagaOutboxEvents.stream()
            .filter(e -> "saga.reply.tax-invoice".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice outbox event"));

        String payload = (String) replyEvent.get("payload");
        assertThat(payload).contains("SUCCESS");
        assertThat(payload).contains(correlationId);
        assertThat(replyEvent.get("partition_key")).isEqualTo(sagaId);
    }

    // ========== CDC Flow Tests ==========

    @Test
    @DisplayName("Should publish TaxInvoiceProcessedEvent to Kafka via CDC")
    void shouldPublishProcessedEventViaCdc() throws Exception {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sagaCommandHandler.handleProcessCommand(command);
        Map<String, Object> invoice = getInvoiceBySourceId(documentId);
        String invoiceId = invoice.get("id").toString();

        // Then — await message on taxinvoice.processed topic
        await().atMost(2, MINUTES).pollInterval(2, SECONDS)
               .until(() -> hasMessageOnTopic("taxinvoice.processed", invoiceId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("taxinvoice.processed", invoiceId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = parseJson(messages.get(0).value());
        assertThat(payload.has("documentNumber")).isTrue();
        assertThat(payload.get("documentNumber").asText()).isEqualTo(invoiceNumber);
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("Should publish saga SUCCESS reply to Kafka via CDC")
    void shouldPublishSagaReplyViaCdc() throws Exception {
        // Given
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = UUID.randomUUID().toString();
        String sagaId = "saga-" + correlationId;

        ProcessTaxInvoiceCommand command = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), correlationId);

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then — await message on saga.reply.tax-invoice topic
        await().atMost(2, MINUTES).pollInterval(2, SECONDS)
               .until(() -> hasMessageOnTopic("saga.reply.tax-invoice", sagaId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("saga.reply.tax-invoice", sagaId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = parseJson(messages.get(0).value());
        assertThat(payload.has("status")).isTrue();
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(payload.get("sagaId").asText()).isEqualTo(sagaId);
    }

    // ========== Compensation Tests ==========

    @Test
    @DisplayName("Should write COMPENSATED reply to outbox and delete invoice from DB")
    void shouldWriteCompensatedReplyToOutbox() {
        // Given — process first so there is something to compensate
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String processCorrelationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand processCommand = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), processCorrelationId);
        sagaCommandHandler.handleProcessCommand(processCommand);
        assertThat(getInvoiceBySourceId(documentId)).isNotNull();

        // When — compensate
        String compensateCorrelationId = UUID.randomUUID().toString();
        String compensateSagaId = "saga-" + compensateCorrelationId;
        CompensateTaxInvoiceCommand compensateCommand = createCompensateTaxInvoiceCommand(
            documentId, compensateCorrelationId);
        sagaCommandHandler.handleCompensation(compensateCommand);

        // Then — invoice hard-deleted from DB
        assertThat(getInvoiceBySourceId(documentId)).isNull();

        // COMPENSATED reply written to outbox
        List<Map<String, Object>> sagaOutboxEvents = getOutboxEvents(compensateSagaId);
        assertThat(sagaOutboxEvents).isNotEmpty();
        Map<String, Object> replyEvent = sagaOutboxEvents.stream()
            .filter(e -> "saga.reply.tax-invoice".equals(e.get("topic")))
            .findFirst().orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice outbox event after compensation"));
        String payload = (String) replyEvent.get("payload");
        assertThat(payload).contains("COMPENSATED");
        assertThat(payload).contains(compensateCorrelationId);
        assertThat(replyEvent.get("partition_key")).isEqualTo(compensateSagaId);
    }

    @Test
    @DisplayName("Should publish COMPENSATED reply to Kafka via CDC")
    void shouldPublishCompensatedReplyViaCdc() throws Exception {
        // Given — process first
        String documentId = "DOC-" + UUID.randomUUID();
        String invoiceNumber = "TV-" + UUID.randomUUID().toString().substring(0, 8);
        String processCorrelationId = UUID.randomUUID().toString();

        ProcessTaxInvoiceCommand processCommand = createProcessTaxInvoiceCommand(
            documentId, invoiceNumber, getSampleTaxInvoiceXml(invoiceNumber), processCorrelationId);
        sagaCommandHandler.handleProcessCommand(processCommand);

        // When — compensate
        String compensateCorrelationId = UUID.randomUUID().toString();
        String compensateSagaId = "saga-" + compensateCorrelationId;
        CompensateTaxInvoiceCommand compensateCommand = createCompensateTaxInvoiceCommand(
            documentId, compensateCorrelationId);
        sagaCommandHandler.handleCompensation(compensateCommand);

        // Then — await COMPENSATED reply on saga.reply.tax-invoice topic via CDC
        await().atMost(2, MINUTES).pollInterval(2, SECONDS)
               .until(() -> hasMessageOnTopic("saga.reply.tax-invoice", compensateSagaId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("saga.reply.tax-invoice", compensateSagaId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = parseJson(messages.get(0).value());
        assertThat(payload.get("status").asText()).isEqualTo("COMPENSATED");
        assertThat(payload.get("correlationId").asText()).isEqualTo(compensateCorrelationId);
        assertThat(payload.get("sagaId").asText()).isEqualTo(compensateSagaId);
    }
}
