package com.wpanther.taxinvoice.processing.domain.model;

/**
 * Enum representing the processing status of a tax invoice.
 *
 * <p>In the Saga Orchestration pattern, processing failures are communicated
 * via {@code FAILURE} saga replies published to the outbox and forwarded to
 * the orchestrator over Kafka. The outer transaction rolls back on failure,
 * leaving no persistent record. Failure diagnosis therefore uses metrics
 * ({@code taxinvoice.processing.failure}) and Kafka/application logs, not
 * database queries. A FAILED status value is intentionally absent to prevent
 * the domain model from advertising a persistence capability that the service
 * does not deliver.
 */
public enum ProcessingStatus {
    /**
     * Tax invoice has been received and is pending processing
     */
    PENDING,

    /**
     * Tax invoice is currently being processed
     */
    PROCESSING,

    /**
     * Tax invoice has been successfully processed
     */
    COMPLETED
}
