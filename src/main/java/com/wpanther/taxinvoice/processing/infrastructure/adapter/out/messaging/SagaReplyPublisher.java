package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging.dto.TaxInvoiceReplyEvent;
import com.wpanther.taxinvoice.processing.application.port.out.SagaReplyPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Saga reply publisher - driven adapter that publishes saga replies via outbox pattern.
 * Replies are sent to the orchestrator via configurable saga.reply.tax-invoice topic.
 */
@Component
@Slf4j
public class SagaReplyPublisher implements SagaReplyPort {

    private static final String AGGREGATE_TYPE = "ProcessedTaxInvoice";

    private final OutboxService outboxService;
    private final HeaderSerializer headerSerializer;
    private final String replyTopic;

    public SagaReplyPublisher(
            OutboxService outboxService,
            HeaderSerializer headerSerializer,
            @Value("${app.kafka.topics.saga-reply-tax-invoice}") String replyTopic) {
        this.outboxService = outboxService;
        this.headerSerializer = headerSerializer;
        this.replyTopic = replyTopic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId) {
        TaxInvoiceReplyEvent reply = TaxInvoiceReplyEvent.success(sagaId, sagaStep, correlationId);

        Map<String, String> headers = Map.of(
            "sagaId", sagaId,
            "correlationId", correlationId,
            "status", "SUCCESS"
        );

        outboxService.saveWithRouting(
            reply,
            AGGREGATE_TYPE,
            sagaId,
            replyTopic,
            sagaId,
            headerSerializer.toJson(headers)
        );

        log.info("Published SUCCESS saga reply for saga {} step {}", sagaId, sagaStep);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        TaxInvoiceReplyEvent reply = TaxInvoiceReplyEvent.failure(sagaId, sagaStep, correlationId, errorMessage);

        Map<String, String> headers = Map.of(
            "sagaId", sagaId,
            "correlationId", correlationId,
            "status", "FAILURE"
        );

        outboxService.saveWithRouting(
            reply,
            AGGREGATE_TYPE,
            sagaId,
            replyTopic,
            sagaId,
            headerSerializer.toJson(headers)
        );

        log.info("Published FAILURE saga reply for saga {} step {}: {}", sagaId, sagaStep, errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        TaxInvoiceReplyEvent reply = TaxInvoiceReplyEvent.compensated(sagaId, sagaStep, correlationId);

        Map<String, String> headers = Map.of(
            "sagaId", sagaId,
            "correlationId", correlationId,
            "status", "COMPENSATED"
        );

        outboxService.saveWithRouting(
            reply,
            AGGREGATE_TYPE,
            sagaId,
            replyTopic,
            sagaId,
            headerSerializer.toJson(headers)
        );

        log.info("Published COMPENSATED saga reply for saga {} step {}", sagaId, sagaStep);
    }

}
