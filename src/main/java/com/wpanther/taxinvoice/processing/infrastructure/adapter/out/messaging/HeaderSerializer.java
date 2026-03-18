package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Header serializer for outbox events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeaderSerializer {

    private final ObjectMapper objectMapper;

    /**
     * Serialize outbox event headers to JSON.
     *
     * <p>Throws {@link IllegalStateException} on serialization failure rather than returning
     * an empty-headers fallback. An empty fallback would silently write the outbox event
     * without {@code sagaId} / {@code correlationId} headers, causing Debezium to publish
     * a Kafka message that downstream consumers cannot match to a saga instance. Throwing
     * instead rolls back the outbox write transaction, letting Camel retry the entire
     * message delivery.
     *
     * @throws IllegalStateException if the headers map cannot be serialized to JSON
     */
    public String toJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox headers to JSON: " + e.getMessage(), e);
        }
    }
}
