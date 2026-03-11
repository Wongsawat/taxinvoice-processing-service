package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeaderSerializerTest {

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private HeaderSerializer headerSerializer;

    @Test
    void toJson_successfulSerialization() throws JsonProcessingException {
        Map<String, String> headers = Map.of("key", "value");
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"key\":\"value\"}");

        String result = headerSerializer.toJson(headers);

        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void toJson_whenJsonProcessingException_returnsEmptyJson() throws JsonProcessingException {
        Map<String, String> headers = Map.of("key", "value");
        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("error") {});

        String result = headerSerializer.toJson(headers);

        assertEquals("{}", result);
    }
}
