package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaxInvoiceLineItemEntity
 */
class TaxInvoiceLineItemEntityTest {

    @Test
    void testBuilderAndGetters() {
        ProcessedTaxInvoiceEntity invoice = ProcessedTaxInvoiceEntity.builder().build();
        UUID id = UUID.randomUUID();

        TaxInvoiceLineItemEntity lineItem = TaxInvoiceLineItemEntity.builder()
            .id(id)
            .invoice(invoice)
            .lineNumber(1)
            .description("Test Item")
            .quantity(5)
            .unitPrice(new BigDecimal("100.00"))
            .taxRate(new BigDecimal("7.00"))
            .lineTotal(new BigDecimal("500.00"))
            .taxAmount(new BigDecimal("35.00"))
            .build();

        assertEquals(id, lineItem.getId());
        assertEquals(invoice, lineItem.getInvoice());
        assertEquals(1, lineItem.getLineNumber());
        assertEquals("Test Item", lineItem.getDescription());
        assertEquals(5, lineItem.getQuantity());
    }

    @Test
    void testNoArgsConstructor() {
        TaxInvoiceLineItemEntity lineItem = new TaxInvoiceLineItemEntity();
        assertNotNull(lineItem);
        assertNull(lineItem.getId());
    }

    @Test
    void testAllArgsConstructor() {
        UUID id = UUID.randomUUID();
        ProcessedTaxInvoiceEntity invoice = new ProcessedTaxInvoiceEntity();

        TaxInvoiceLineItemEntity lineItem = new TaxInvoiceLineItemEntity(
            id, invoice, 1, "Item", 2,
            new BigDecimal("100.00"), new BigDecimal("7.00"),
            new BigDecimal("200.00"), new BigDecimal("14.00")
        );

        assertEquals(id, lineItem.getId());
        assertEquals("Item", lineItem.getDescription());
    }
}
