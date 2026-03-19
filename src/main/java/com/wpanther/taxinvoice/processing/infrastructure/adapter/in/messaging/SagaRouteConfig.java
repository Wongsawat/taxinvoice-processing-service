package com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging;

import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.CompensateTaxInvoiceCommand;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.in.messaging.dto.ProcessTaxInvoiceCommand;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Camel routes for saga command and compensation consumers.
 * Replaces the old TaxInvoiceRouteConfig that consumed from document.received.tax-invoice.
 */
@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private static final String GROUP_ID = "taxinvoice-processing-service";

    private final SagaCommandHandler sagaCommandHandler;

    @Value("${app.kafka.bootstrap-servers}")
    private String kafkaBrokers;

    @Value("${app.kafka.topics.saga-command-tax-invoice}")
    private String sagaCommandTopic;

    @Value("${app.kafka.topics.saga-compensation-tax-invoice}")
    private String sagaCompensationTopic;

    @Value("${app.kafka.topics.dlq}")
    private String dlqTopic;

    @Value("${app.camel.retry.max-redeliveries:3}")
    private int maxRedeliveries;

    @Value("${app.camel.retry.redelivery-delay-ms:1000}")
    private long redeliveryDelayMs;

    @Value("${app.camel.retry.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    @Value("${app.camel.retry.max-redelivery-delay-ms:10000}")
    private long maxRedeliveryDelayMs;

    @Value("${app.kafka.consumers.max-poll-records:100}")
    private int maxPollRecords;

    @Value("${app.kafka.consumers.count:3}")
    private int consumersCount;

    public SagaRouteConfig(SagaCommandHandler sagaCommandHandler) {
        this.sagaCommandHandler = sagaCommandHandler;
    }

    /**
     * Build common Kafka consumer parameters.
     */
    private String kafkaConsumerParams() {
        return "?brokers=RAW(" + kafkaBrokers + ")"
                + "&groupId=" + GROUP_ID
                + "&autoOffsetReset=earliest"
                + "&autoCommitEnable=false"
                + "&breakOnFirstError=true"
                + "&maxPollRecords=" + maxPollRecords
                + "&consumersCount=" + consumersCount;
    }

    @Override
    public void configure() throws Exception {

        // Global error handler - Dead Letter Channel with retries
        errorHandler(deadLetterChannel("kafka:" + dlqTopic + "?brokers=RAW(" + kafkaBrokers + ")")
            .maximumRedeliveries(maxRedeliveries)
            .redeliveryDelay(redeliveryDelayMs)
            .useExponentialBackOff()
            .backOffMultiplier(backoffMultiplier)
            .maximumRedeliveryDelay(maxRedeliveryDelayMs)
            .logExhausted(true)
            .logStackTrace(true));

        // ============================================================
        // CONSUMER ROUTE: saga.command.tax-invoice (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCommandTopic + kafkaConsumerParams())
            .routeId("saga-command-consumer")
            .log("Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
            .unmarshal().json(JsonLibrary.Jackson, ProcessTaxInvoiceCommand.class)
            .process(exchange -> {
                ProcessTaxInvoiceCommand cmd = exchange.getIn().getBody(ProcessTaxInvoiceCommand.class);
                log.info("Processing saga command for saga: {}, invoice: {}",
                    cmd.getSagaId(), cmd.getInvoiceNumber());
                sagaCommandHandler.handleProcessCommand(cmd);
            })
            .log("Successfully processed saga command");

        // ============================================================
        // CONSUMER ROUTE: saga.compensation.tax-invoice (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCompensationTopic + kafkaConsumerParams())
            .routeId("saga-compensation-consumer")
            .log("Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
            .unmarshal().json(JsonLibrary.Jackson, CompensateTaxInvoiceCommand.class)
            .process(exchange -> {
                CompensateTaxInvoiceCommand cmd = exchange.getIn().getBody(CompensateTaxInvoiceCommand.class);
                log.info("Processing compensation for saga: {}, document: {}",
                    cmd.getSagaId(), cmd.getDocumentId());
                sagaCommandHandler.handleCompensation(cmd);
            })
            .log("Successfully processed compensation command");
    }
}
