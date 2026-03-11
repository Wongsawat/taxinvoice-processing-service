package com.wpanther.taxinvoice.processing.domain.port.out;

import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for ProcessedTaxInvoice repository operations.
 * This is a domain-level port that works with domain objects, not JPA entities.
 */
public interface ProcessedTaxInvoiceRepository {

    /**
     * Save a processed tax invoice
     */
    ProcessedTaxInvoice save(ProcessedTaxInvoice invoice);

    /**
     * Find invoice by ID
     */
    Optional<ProcessedTaxInvoice> findById(TaxInvoiceId id);

    /**
     * Find invoice by invoice number
     */
    Optional<ProcessedTaxInvoice> findByInvoiceNumber(String invoiceNumber);

    /**
     * Find invoices by status
     */
    List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status);

    /**
     * Find invoice by source invoice ID
     */
    Optional<ProcessedTaxInvoice> findBySourceInvoiceId(String sourceInvoiceId);

    /**
     * Check if invoice number already exists
     */
    boolean existsByInvoiceNumber(String invoiceNumber);

    /**
     * Delete invoice by ID
     */
    void deleteById(TaxInvoiceId id);
}
