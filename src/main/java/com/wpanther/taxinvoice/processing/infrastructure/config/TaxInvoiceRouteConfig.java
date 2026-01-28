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
 * Replaces Spring Kafka consumer and producer configuration.
 */
@Component
@Slf4j
public class TaxInvoiceRouteConfig extends RouteBuilder {

    private final TaxInvoiceProcessingService processingService;

    @Value("${app.kafka.bootstrap-servers}")
    private String kafkaBrokers;

    @Value("${app.kafka.topics.document-received-taxinvoice}")
    private String inputTopic;

    @Value("${app.kafka.topics.taxinvoice-processed}")
    private String taxinvoiceProcessedTopic;

    @Value("${app.kafka.topics.xml-signing-requested}")
    private String xmlSigningRequestedTopic;

    @Value("${app.kafka.topics.pdf-generation-requested}")
    private String pdfGenerationRequestedTopic;

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

                // Call existing application service (unchanged)
                processingService.processInvoiceReceived(event);
            })

            .log("Successfully processed tax invoice");

        // ============================================================
        // PRODUCER ROUTE: taxinvoice.processed
        // ============================================================
        from("direct:publish-taxinvoice-processed")
            .routeId("taxinvoice-processed-producer")
            .log("Publishing TaxInvoiceProcessedEvent: ${body.invoiceNumber}")
            .marshal().json(JsonLibrary.Jackson)
            .to("kafka:" + taxinvoiceProcessedTopic
                + "?brokers=" + kafkaBrokers
                + "&key=${header.kafka.KEY}")
            .log("Published TaxInvoiceProcessedEvent to " + taxinvoiceProcessedTopic);

        // ============================================================
        // PRODUCER ROUTE: xml.signing.requested
        // ============================================================
        from("direct:publish-xml-signing-requested")
            .routeId("xml-signing-requested-producer")
            .log("Publishing XmlSigningRequestedEvent: ${body.invoiceNumber}")
            .marshal().json(JsonLibrary.Jackson)
            .to("kafka:" + xmlSigningRequestedTopic
                + "?brokers=" + kafkaBrokers
                + "&key=${header.kafka.KEY}")
            .log("Published XmlSigningRequestedEvent to " + xmlSigningRequestedTopic);

        // ============================================================
        // PRODUCER ROUTE: pdf.generation.requested
        // ============================================================
        from("direct:publish-pdf-generation-requested")
            .routeId("pdf-generation-requested-producer")
            .log("Publishing PdfGenerationRequestedEvent: ${body.invoiceNumber}")
            .marshal().json(JsonLibrary.Jackson)
            .to("kafka:" + pdfGenerationRequestedTopic
                + "?brokers=" + kafkaBrokers
                + "&key=${header.kafka.KEY}")
            .log("Published PdfGenerationRequestedEvent to " + pdfGenerationRequestedTopic);
    }
}
