package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.processing.domain.model.*;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProcessedTaxInvoiceRepositoryImpl
 */
@DataJpaTest
@Import({ProcessedTaxInvoiceRepositoryImpl.class, ProcessedTaxInvoiceMapper.class})
@ActiveProfiles("test")
class ProcessedTaxInvoiceRepositoryImplTest {

    @Autowired
    private ProcessedTaxInvoiceRepository repository;

    private ProcessedTaxInvoice testInvoice;

    @BeforeEach
    void setUp() {
        Party seller = Party.of(
            "Test Seller",
            TaxIdentifier.of("1234567890", "VAT"),
            new Address("123 Street", "Bangkok", "10110", "TH"),
            "seller@test.com"
        );

        Party buyer = Party.of(
            "Test Buyer",
            TaxIdentifier.of("9876543210", "VAT"),
            new Address("456 Road", "Chiang Mai", "50000", "TH"),
            "buyer@test.com"
        );

        LineItem item = new LineItem(
            "Test Service",
            10,
            Money.of(new BigDecimal("1000.00"), "THB"),
            new BigDecimal("7.00")
        );

        testInvoice = ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.generate())
            .sourceInvoiceId("intake-test-123")
            .invoiceNumber("TXN-TEST-001")
            .issueDate(LocalDate.of(2025, 1, 1))
            .dueDate(LocalDate.of(2025, 2, 1))
            .seller(seller)
            .buyer(buyer)
            .addItem(item)
            .currency("THB")
            .originalXml("<xml>test</xml>")
            .build();
    }

    @Test
    void testSaveAndFindById() {
        // When
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);
        Optional<ProcessedTaxInvoice> found = repository.findById(saved.getId());

        // Then
        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(saved.getInvoiceNumber(), found.get().getInvoiceNumber());
        assertEquals(saved.getSourceInvoiceId(), found.get().getSourceInvoiceId());
    }

    @Test
    void testFindByIdNotFound() {
        // Given
        TaxInvoiceId nonExistentId = TaxInvoiceId.generate();

        // When
        Optional<ProcessedTaxInvoice> found = repository.findById(nonExistentId);

        // Then
        assertFalse(found.isPresent());
    }

    @Test
    void testFindBySourceInvoiceId() {
        // Given
        testInvoice.startProcessing();
        repository.save(testInvoice);

        // When
        Optional<ProcessedTaxInvoice> found = repository.findBySourceInvoiceId("intake-test-123");

        // Then
        assertTrue(found.isPresent());
        assertEquals("intake-test-123", found.get().getSourceInvoiceId());
        assertEquals("TXN-TEST-001", found.get().getInvoiceNumber());
    }

    @Test
    void testFindBySourceInvoiceIdNotFound() {
        // When
        Optional<ProcessedTaxInvoice> found = repository.findBySourceInvoiceId("non-existent");

        // Then
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByStatus() {
        // Given — PROCESSING is the first persisted status (service never saves PENDING)
        testInvoice.startProcessing();
        repository.save(testInvoice);

        // When
        List<ProcessedTaxInvoice> found = repository.findByStatus(ProcessingStatus.PROCESSING);

        // Then
        assertFalse(found.isEmpty());
        assertTrue(found.stream().anyMatch(i -> i.getId().equals(testInvoice.getId())));
    }

    @Test
    void testFindByStatusEmpty() {
        // When
        List<ProcessedTaxInvoice> found = repository.findByStatus(ProcessingStatus.COMPLETED);

        // Then
        assertTrue(found.isEmpty());
    }

    @Test
    void testSavePreservesAllFields() {
        // When
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);
        Optional<ProcessedTaxInvoice> found = repository.findById(saved.getId());

        // Then
        assertTrue(found.isPresent());
        ProcessedTaxInvoice invoice = found.get();

        // Check all fields
        assertEquals(testInvoice.getInvoiceNumber(), invoice.getInvoiceNumber());
        assertEquals(testInvoice.getIssueDate(), invoice.getIssueDate());
        assertEquals(testInvoice.getDueDate(), invoice.getDueDate());
        assertEquals(testInvoice.getCurrency(), invoice.getCurrency());
        assertEquals(testInvoice.getStatus(), invoice.getStatus());
        assertEquals(testInvoice.getOriginalXml(), invoice.getOriginalXml());

        // Check calculated totals
        assertEquals(testInvoice.getSubtotal(), invoice.getSubtotal());
        assertEquals(testInvoice.getTotalTax(), invoice.getTotalTax());
        assertEquals(testInvoice.getTotal(), invoice.getTotal());

        // Check parties
        assertNotNull(invoice.getSeller());
        assertEquals(testInvoice.getSeller().name(), invoice.getSeller().name());
        assertNotNull(invoice.getBuyer());
        assertEquals(testInvoice.getBuyer().name(), invoice.getBuyer().name());

        // Check line items
        assertEquals(testInvoice.getItems().size(), invoice.getItems().size());
    }

    @Test
    void testJpaSave_mergesExistingEntityByPrimaryKey() {
        // When save() is called twice with PROCESSING status it takes the INSERT branch
        // (jpaRepository.save) both times. JPA recognises the existing PK and performs
        // a merge (UPDATE), not a second INSERT. This exercises the JPA merge path,
        // NOT the updateStatusFields() path — that path requires a non-PROCESSING status.
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);

        ProcessedTaxInvoice merged = repository.save(saved);
        Optional<ProcessedTaxInvoice> found = repository.findById(merged.getId());

        assertTrue(found.isPresent());
        assertEquals(ProcessingStatus.PROCESSING, found.get().getStatus());
    }

    @Test
    void testSaveContractViolation_throwsIllegalStateExceptionOnUnpersistedNonProcessingStatus() {
        // Given: invoice has status COMPLETED but was never persisted in PROCESSING state
        testInvoice.startProcessing();
        testInvoice.markCompleted(); // status = COMPLETED, no INSERT has happened

        // When/Then: save() detects no row exists and throws rather than silently
        // issuing a zero-row UPDATE that would drop the invoice without an error
        assertThrows(IllegalStateException.class, () -> repository.save(testInvoice),
            "save() must throw IllegalStateException when called with non-PROCESSING " +
            "status for an invoice that was never persisted");
    }

    @Test
    void testSaveMultipleInvoices() {
        // Given
        ProcessedTaxInvoice invoice2 = ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.generate())
            .sourceInvoiceId("intake-test-456")
            .invoiceNumber("TXN-TEST-002")
            .issueDate(LocalDate.of(2025, 1, 2))
            .dueDate(LocalDate.of(2025, 2, 2))
            .seller(testInvoice.getSeller())
            .buyer(testInvoice.getBuyer())
            .items(testInvoice.getItems())
            .currency("THB")
            .originalXml("<xml>test2</xml>")
            .build();

        // When
        testInvoice.startProcessing();
        invoice2.startProcessing();
        repository.save(testInvoice);
        repository.save(invoice2);

        // Then
        Optional<ProcessedTaxInvoice> found1 = repository.findBySourceInvoiceId("intake-test-123");
        Optional<ProcessedTaxInvoice> found2 = repository.findBySourceInvoiceId("intake-test-456");

        assertTrue(found1.isPresent());
        assertTrue(found2.isPresent());
        assertNotEquals(found1.get().getId(), found2.get().getId());
    }

    @Test
    void testFindByStatusMultipleInvoices() {
        // Given
        ProcessedTaxInvoice invoice2 = ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.generate())
            .sourceInvoiceId("intake-test-456")
            .invoiceNumber("TXN-TEST-002")
            .issueDate(LocalDate.of(2025, 1, 2))
            .dueDate(LocalDate.of(2025, 2, 2))
            .seller(testInvoice.getSeller())
            .buyer(testInvoice.getBuyer())
            .items(testInvoice.getItems())
            .currency("THB")
            .originalXml("<xml>test2</xml>")
            .build();

        testInvoice.startProcessing();
        invoice2.startProcessing();
        repository.save(testInvoice);
        repository.save(invoice2);

        // When
        List<ProcessedTaxInvoice> found = repository.findByStatus(ProcessingStatus.PROCESSING);

        // Then
        assertEquals(2, found.size());
    }

    @Test
    void testCompleteWorkflowWithRepository() {
        // Given — honour the INSERT/UPDATE contract: first save must be PROCESSING
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);

        // When - Simulate complete workflow (saved is already PROCESSING)
        repository.save(saved);

        saved.markCompleted();
        ProcessedTaxInvoice finalInvoice = repository.save(saved);

        // Then
        Optional<ProcessedTaxInvoice> found = repository.findById(finalInvoice.getId());
        assertTrue(found.isPresent());
        assertEquals(ProcessingStatus.COMPLETED, found.get().getStatus());
        assertNotNull(found.get().getCompletedAt());
    }

    @Test
    void testFindByInvoiceNumber_found() {
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);
        Optional<ProcessedTaxInvoice> found = repository.findByInvoiceNumber(saved.getInvoiceNumber());
        assertTrue(found.isPresent());
        assertEquals(saved.getInvoiceNumber(), found.get().getInvoiceNumber());
    }

    @Test
    void testFindByInvoiceNumber_notFound() {
        Optional<ProcessedTaxInvoice> found = repository.findByInvoiceNumber("NON-EXISTENT");
        assertFalse(found.isPresent());
    }

    @Test
    void testDeleteById() {
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);
        repository.deleteById(saved.getId());
        assertFalse(repository.findById(saved.getId()).isPresent());
    }

    @Autowired
    private JpaProcessedTaxInvoiceRepository jpaRepository;

    @Test
    void testUpdateStatusFields_incrementsVersionAndSetsUpdatedAt() {
        // Given: PROCESSING is the first persisted status; INSERT gives version = 0.
        // Note: in Hibernate 6, @UpdateTimestamp fires on UPDATE only, so updatedAt
        // is null after INSERT. The important thing is that updateStatusFields() sets it.
        testInvoice.startProcessing();
        ProcessedTaxInvoice saved = repository.save(testInvoice);
        UUID id = saved.getId().value();
        assertEquals(0L, jpaRepository.findById(id).orElseThrow().getVersion(),
            "Version must be 0 after initial INSERT");

        // When: status update (PROCESSING → COMPLETED) via updateStatusFields
        saved.markCompleted();
        repository.save(saved);

        // Then: version incremented and updatedAt set by i.updatedAt = CURRENT_TIMESTAMP
        // in the JPQL SET clause — verifies the @Modifying bypass of @UpdateTimestamp
        // is compensated for explicitly in the query.
        ProcessedTaxInvoiceEntity afterUpdate = jpaRepository.findById(id).orElseThrow();
        assertEquals(1L, afterUpdate.getVersion(),
            "Version must be 1 after updateStatusFields call");
        assertNotNull(afterUpdate.getUpdatedAt(),
            "updatedAt must be set by updateStatusFields() JPQL — " +
            "verifies i.updatedAt = CURRENT_TIMESTAMP is present in the SET clause");
    }
}
