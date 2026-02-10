package com.wpanther.taxinvoice.processing.infrastructure.config;

import com.wpanther.taxinvoice.processing.application.service.TaxInvoiceProcessingService;
import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

@CamelSpringBootTest
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("TaxInvoiceRouteConfig Tests")
class TaxInvoiceRouteConfigTest {

    @Autowired
    private CamelContext camelContext;

    @MockBean
    private TaxInvoiceProcessingService processingService;

    @Test
    @DisplayName("Should have all routes configured")
    void shouldHaveAllRoutesConfigured() {
        // Assert - only consumer route (producers removed - outbox pattern handles publishing)
        assertNotNull(camelContext.getRoute("taxinvoice-processing-consumer"),
            "Consumer route should be configured");
    }

    @Test
    @DisplayName("Should have correct number of routes")
    void shouldHaveCorrectNumberOfRoutes() {
        // We expect 1 route: 1 consumer (producers removed - outbox pattern handles publishing)
        assertEquals(1, camelContext.getRoutes().size(),
            "Should have exactly 1 route configured");
    }

    @Test
    @DisplayName("Camel context should be started")
    void camelContextShouldBeStarted() {
        // Assert
        assertNotNull(camelContext, "Camel context should be available");
    }
}
