package com.wpanther.taxinvoice.processing.infrastructure.messaging;

import com.wpanther.taxinvoice.processing.application.service.TaxInvoiceProcessingService;
import com.wpanther.taxinvoice.processing.domain.event.TaxInvoiceReceivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for tax invoice events
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceEventListener {

    private final TaxInvoiceProcessingService processingService;

    /**
     * Listen for tax invoice received events from intake service
     */
    @KafkaListener(
        topics = "${app.kafka.topics.document-received-taxinvoice}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTaxInvoiceReceived(
        @Payload TaxInvoiceReceivedEvent event,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        Acknowledgment acknowledgment
    ) {
        log.info("Received tax invoice event: {} (partition: {}, offset: {})",
            event.getInvoiceNumber(), partition, offset);

        try {
            // Process the tax invoice
            processingService.processInvoiceReceived(event);

            // Acknowledge message
            acknowledgment.acknowledge();
            log.debug("Acknowledged tax invoice received event: {}", event.getInvoiceNumber());

        } catch (Exception e) {
            log.error("Error processing tax invoice received event: {}", event.getInvoiceNumber(), e);
            // Don't acknowledge - message will be retried
            // In production, implement DLQ after max retries
        }
    }
}
