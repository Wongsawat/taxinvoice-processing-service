package com.wpanther.taxinvoice.processing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaxInvoiceProcessingServiceApplication
 */
@SpringBootTest
@ActiveProfiles("test")
class TaxInvoiceProcessingServiceApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // Then
        assertNotNull(applicationContext, "Application context should load successfully");
    }

    @Test
    void testApplicationHasRequiredBeans() {
        // Then
        assertTrue(applicationContext.containsBean("taxInvoiceProcessingService"),
            "Should have TaxInvoiceProcessingService bean");
        assertTrue(applicationContext.containsBean("eventPublisher"),
            "Should have EventPublisher bean");
        assertTrue(applicationContext.containsBean("taxInvoiceEventListener"),
            "Should have TaxInvoiceEventListener bean");
    }
}
