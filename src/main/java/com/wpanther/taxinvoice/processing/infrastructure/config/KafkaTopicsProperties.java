package com.wpanther.taxinvoice.processing.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for all {@code app.kafka.topics.*} configuration properties.
 *
 * <p>Centralises the property keys in one place so that a mistyped key fails at
 * startup (Spring Boot throws {@code BindException} for unresolvable fields) rather
 * than silently routing events to the wrong topic at runtime.
 *
 * <p>YAML key mapping (Spring relaxed binding):
 * <pre>
 *   app.kafka.topics.taxinvoice-processed         → taxinvoiceProcessed
 *   app.kafka.topics.dlq                          → dlq
 *   app.kafka.topics.saga-command-tax-invoice     → sagaCommandTaxInvoice
 *   app.kafka.topics.saga-compensation-tax-invoice → sagaCompensationTaxInvoice
 *   app.kafka.topics.saga-reply-tax-invoice       → sagaReplyTaxInvoice
 * </pre>
 */
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicsProperties(
        String taxinvoiceProcessed,
        String dlq,
        String sagaCommandTaxInvoice,
        String sagaCompensationTaxInvoice,
        String sagaReplyTaxInvoice) {
}
