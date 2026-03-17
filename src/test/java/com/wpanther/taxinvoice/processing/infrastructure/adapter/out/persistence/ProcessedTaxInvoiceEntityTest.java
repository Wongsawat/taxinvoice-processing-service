package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProcessedTaxInvoiceEntity
 */
class ProcessedTaxInvoiceEntityTest {

    @Test
    void testBuilderCreateEntityWithAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        String sourceId = "source-123";
        String invoiceNumber = "TAX-INV-001";
        LocalDate issueDate = LocalDate.of(2025, 1, 1);
        LocalDate dueDate = LocalDate.of(2025, 2, 1);
        String currency = "THB";
        BigDecimal subtotal = new BigDecimal("1000.00");
        BigDecimal totalTax = new BigDecimal("70.00");
        BigDecimal total = new BigDecimal("1070.00");
        String originalXml = "<xml>test</xml>";
        ProcessingStatus status = ProcessingStatus.COMPLETED;
        String errorMessage = "Test error";
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime completedAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();

        // When
        ProcessedTaxInvoiceEntity entity = ProcessedTaxInvoiceEntity.builder()
            .id(id)
            .sourceInvoiceId(sourceId)
            .invoiceNumber(invoiceNumber)
            .issueDate(issueDate)
            .dueDate(dueDate)
            .currency(currency)
            .subtotal(subtotal)
            .totalTax(totalTax)
            .total(total)
            .originalXml(originalXml)
            .status(status)
            .errorMessage(errorMessage)
            .createdAt(createdAt)
            .completedAt(completedAt)
            .updatedAt(updatedAt)
            .parties(new HashSet<>())
            .lineItems(new ArrayList<>())
            .build();

        // Then
        assertEquals(id, entity.getId());
        assertEquals(sourceId, entity.getSourceInvoiceId());
        assertEquals(invoiceNumber, entity.getInvoiceNumber());
        assertEquals(issueDate, entity.getIssueDate());
        assertEquals(dueDate, entity.getDueDate());
        assertEquals(currency, entity.getCurrency());
        assertEquals(subtotal, entity.getSubtotal());
        assertEquals(totalTax, entity.getTotalTax());
        assertEquals(total, entity.getTotal());
        assertEquals(originalXml, entity.getOriginalXml());
        assertEquals(status, entity.getStatus());
        assertEquals(errorMessage, entity.getErrorMessage());
        assertEquals(createdAt, entity.getCreatedAt());
        assertEquals(completedAt, entity.getCompletedAt());
        assertEquals(updatedAt, entity.getUpdatedAt());
        assertNotNull(entity.getParties());
        assertNotNull(entity.getLineItems());
    }

    @Test
    void testMutableFieldSetters() {
        // Only status, completedAt, and errorMessage have setters — all other fields
        // are set once via the builder and must not change after creation.
        ProcessedTaxInvoiceEntity entity = ProcessedTaxInvoiceEntity.builder()
            .id(UUID.randomUUID())
            .sourceInvoiceId("source-123")
            .invoiceNumber("TAX-INV-001")
            .issueDate(LocalDate.of(2025, 1, 1))
            .dueDate(LocalDate.of(2025, 2, 1))
            .currency("THB")
            .subtotal(new BigDecimal("1000.00"))
            .totalTax(new BigDecimal("70.00"))
            .total(new BigDecimal("1070.00"))
            .originalXml("<xml>test</xml>")
            .status(ProcessingStatus.PENDING)
            .build();

        LocalDateTime completedAt = LocalDateTime.now();
        entity.setStatus(ProcessingStatus.COMPLETED);
        entity.setCompletedAt(completedAt);
        entity.setErrorMessage("Error");

        assertEquals(ProcessingStatus.COMPLETED, entity.getStatus());
        assertEquals(completedAt, entity.getCompletedAt());
        assertEquals("Error", entity.getErrorMessage());
    }

    @Test
    void testAddParty() {
        // Given
        ProcessedTaxInvoiceEntity entity = ProcessedTaxInvoiceEntity.builder()
            .parties(new HashSet<>())
            .build();
        TaxInvoicePartyEntity party = TaxInvoicePartyEntity.builder()
            .partyType(TaxInvoicePartyEntity.PartyType.SELLER)
            .name("Test Company")
            .build();

        // When
        entity.addParty(party);

        // Then
        assertEquals(1, entity.getParties().size());
        assertEquals(entity, party.getInvoice());
    }

    @Test
    void testAddLineItem() {
        // Given
        ProcessedTaxInvoiceEntity entity = ProcessedTaxInvoiceEntity.builder()
            .lineItems(new ArrayList<>())
            .build();
        TaxInvoiceLineItemEntity lineItem = TaxInvoiceLineItemEntity.builder()
            .lineNumber(1)
            .description("Test item")
            .build();

        // When
        entity.addLineItem(lineItem);

        // Then
        assertEquals(1, entity.getLineItems().size());
        assertEquals(entity, lineItem.getInvoice());
    }

    @Test
    void testAllArgsConstructor() {
        // Given
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        // When
        ProcessedTaxInvoiceEntity entity = new ProcessedTaxInvoiceEntity(
            id,
            "source-123",
            "TAX-INV-001",
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 2, 1),
            "THB",
            new BigDecimal("1000.00"),
            new BigDecimal("70.00"),
            new BigDecimal("1070.00"),
            "<xml>test</xml>",
            ProcessingStatus.PENDING,
            null,
            now,
            null,
            now,
            0L,  // version
            new HashSet<>(),
            new ArrayList<>()
        );

        // Then
        assertEquals(id, entity.getId());
        assertEquals("source-123", entity.getSourceInvoiceId());
        assertEquals("TAX-INV-001", entity.getInvoiceNumber());
    }

    @Test
    void testNoArgsConstructor() {
        // When
        ProcessedTaxInvoiceEntity entity = new ProcessedTaxInvoiceEntity();

        // Then
        assertNotNull(entity);
        assertNull(entity.getId());
        assertNull(entity.getInvoiceNumber());
    }
}
