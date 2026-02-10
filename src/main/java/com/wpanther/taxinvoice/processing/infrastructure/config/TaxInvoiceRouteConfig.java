package com.wpanther.taxinvoice.processing.infrastructure.config;

import com.wpanther.taxinvoice.processing.application.service.TaxInvoiceProcessingService;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceReceivedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Camel routes for tax invoice processing.
 * Event publishing now uses Outbox Pattern - only consumer route remains.
 */
@Component
@Slf4j
public class TaxInvoiceRouteConfig extends RouteBuilder {

    private final TaxInvoiceProcessingService processingService;

    @Value("${app.kafka.bootstrap-servers}")
    private String kafkaBrokers;

    @Value("${app.kafka.topics.document-received-taxinvoice}")
    private String inputTopic;

    @Value("${app.kafka.topics.dlq:taxinvoice.processing.dlq}")
    private String dlqTopic;

    public TaxInvoiceRouteConfig(TaxInvoiceProcessingService processingService) {
        this.processingService = processingService;
    }

    @Override
    public void configure() throws Exception {

        // Global error handler - Dead Letter Channel with retries
        errorHandler(deadLetterChannel("kafka:" + dlqTopic + "?brokers=" + kafkaBrokers)
            .maximumRedeliveries(3)
            .redeliveryDelay(1000)
            .useExponentialBackOff()
            .backOffMultiplier(2)
            .maximumRedeliveryDelay(10000)
            .logExhausted(true)
            .logStackTrace(true));

        // ============================================================
        // CONSUMER ROUTE: document.received.tax-invoice
        // ============================================================
        from("kafka:" + inputTopic
                + "?brokers=" + kafkaBrokers
                + "&groupId=taxinvoice-processing-service"
                + "&autoOffsetReset=earliest"
                + "&autoCommitEnable=false"
                + "&breakOnFirstError=true"
                + "&maxPollRecords=100"
                + "&consumersCount=3")
            .routeId("taxinvoice-processing-consumer")
            .log("Received tax invoice from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")

            // Unmarshal JSON to TaxInvoiceReceivedEvent
            .unmarshal().json(JsonLibrary.Jackson, TaxInvoiceReceivedEvent.class)

            // Process the event - call application service
            .process(exchange -> {
                TaxInvoiceReceivedEvent event = exchange.getIn().getBody(TaxInvoiceReceivedEvent.class);
                log.info("Processing tax invoice: {}", event.getInvoiceNumber());

                // Application service uses OutboxService for event publishing
                processingService.processInvoiceReceived(event);
            })

            .log("Successfully processed tax invoice");
    }
}
