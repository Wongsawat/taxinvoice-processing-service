package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceReplyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaReplyPublisherTest {

    @Mock
    private OutboxService outboxService;

    @Mock
    private HeaderSerializer headerSerializer;

    private SagaReplyPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SagaReplyPublisher(outboxService, headerSerializer, "saga.reply.tax-invoice");
    }

    @Test
    void testPublishSuccessCallsOutboxWithCorrectParameters() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{\"sagaId\":\"saga-1\",\"correlationId\":\"corr-1\",\"status\":\"SUCCESS\"}");

        publisher.publishSuccess("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        verify(outboxService).saveWithRouting(
            any(TaxInvoiceReplyEvent.class),
            eq("ProcessedTaxInvoice"),
            eq("saga-1"),
            eq("saga.reply.tax-invoice"),
            eq("saga-1"),
            contains("SUCCESS")
        );
    }

    @Test
    void testPublishSuccessUsesSagaIdAsPartitionKey() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{}");

        publisher.publishSuccess("my-saga-id", SagaStep.SIGN_XML, "corr-1");

        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(
            any(), any(), any(), any(),
            partitionKeyCaptor.capture(),
            any()
        );

        assertEquals("my-saga-id", partitionKeyCaptor.getValue());
    }

    @Test
    void testPublishFailureCallsOutboxWithCorrectParameters() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{\"sagaId\":\"saga-1\",\"correlationId\":\"corr-1\",\"status\":\"FAILURE\"}");

        publisher.publishFailure("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1", "Parse error");

        verify(outboxService).saveWithRouting(
            any(TaxInvoiceReplyEvent.class),
            eq("ProcessedTaxInvoice"),
            eq("saga-1"),
            eq("saga.reply.tax-invoice"),
            eq("saga-1"),
            contains("FAILURE")
        );
    }

    @Test
    void testPublishCompensatedCallsOutboxWithCorrectParameters() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{\"sagaId\":\"saga-1\",\"correlationId\":\"corr-1\",\"status\":\"COMPENSATED\"}");

        publisher.publishCompensated("saga-1", SagaStep.PROCESS_TAX_INVOICE, "corr-1");

        verify(outboxService).saveWithRouting(
            any(TaxInvoiceReplyEvent.class),
            eq("ProcessedTaxInvoice"),
            eq("saga-1"),
            eq("saga.reply.tax-invoice"),
            eq("saga-1"),
            contains("COMPENSATED")
        );
    }

    @Test
    void testPublishSuccessHeadersContainCorrectFields() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{\"sagaId\":\"saga-1\",\"correlationId\":\"corr-1\",\"status\":\"SUCCESS\"}");

        publisher.publishSuccess("saga-1", SagaStep.SIGN_XML, "corr-1");

        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(), headersCaptor.capture());

        String headers = headersCaptor.getValue();
        assertTrue(headers.contains("saga-1"));
        assertTrue(headers.contains("corr-1"));
        assertTrue(headers.contains("SUCCESS"));
    }

    @Test
    void testToJsonErrorReturnsNull() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn(null);

        publisher.publishSuccess("saga-1", SagaStep.SIGN_PDF, "corr-1");

        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(), headersCaptor.capture());

        assertNull(headersCaptor.getValue());
    }

    @Test
    void testPublishReplyEventHasCorrectTopic() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{}");

        publisher.publishSuccess("saga-1", SagaStep.SIGN_XML, "corr-1");

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), topicCaptor.capture(), any(), any());

        assertEquals("saga.reply.tax-invoice", topicCaptor.getValue());
    }

    @Test
    void testPublishReplyEventHasCorrectAggregateType() throws Exception {
        when(headerSerializer.toJson(any())).thenReturn("{}");

        publisher.publishFailure("saga-1", SagaStep.SIGN_PDF, "corr-1", "error");

        ArgumentCaptor<String> aggregateTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), aggregateTypeCaptor.capture(), any(), any(), any(), any());

        assertEquals("ProcessedTaxInvoice", aggregateTypeCaptor.getValue());
    }
}
