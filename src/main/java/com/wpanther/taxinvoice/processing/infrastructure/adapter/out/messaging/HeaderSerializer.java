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

    public String toJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox headers to JSON", e);
            return "{}";
        }
    }
}
