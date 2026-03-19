package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for ProcessedTaxInvoiceEntity
 */
@Repository
public interface JpaProcessedTaxInvoiceRepository extends JpaRepository<ProcessedTaxInvoiceEntity, UUID> {

    /**
     * Find by invoice number with parties and line items eagerly loaded (avoids N+1 queries and LazyInitializationException)
     */
    @Query("SELECT i FROM ProcessedTaxInvoiceEntity i " +
           "LEFT JOIN FETCH i.parties " +
           "LEFT JOIN FETCH i.lineItems " +
           "WHERE i.invoiceNumber = :invoiceNumber")
    Optional<ProcessedTaxInvoiceEntity> findByInvoiceNumberWithDetails(@Param("invoiceNumber") String invoiceNumber);

    /**
     * Find by source invoice ID with parties and line items eagerly loaded (avoids N+1 queries)
     */
    @Query("SELECT i FROM ProcessedTaxInvoiceEntity i " +
           "LEFT JOIN FETCH i.parties " +
           "LEFT JOIN FETCH i.lineItems " +
           "WHERE i.sourceInvoiceId = :sourceInvoiceId")
    Optional<ProcessedTaxInvoiceEntity> findBySourceInvoiceIdWithDetails(@Param("sourceInvoiceId") String sourceInvoiceId);

    /**
     * Check if invoice number exists
     */
    boolean existsByInvoiceNumber(String invoiceNumber);

    /**
     * Update only the mutable state fields (status, errorMessage, completedAt).
     * Used by save() on the update path to avoid loading the full entity.
     *
     * <p>{@code i.version = i.version + 1} keeps the optimistic-locking column
     * consistent with JPA-managed saves. Without this, the version stays at its
     * post-INSERT value after every status transition, so a concurrent entity-level
     * save would pass the {@code WHERE version = ?} check on stale data.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProcessedTaxInvoiceEntity i " +
           "SET i.status = :status, i.errorMessage = :errorMessage, i.completedAt = :completedAt, " +
           "    i.version = i.version + 1 " +
           "WHERE i.id = :id")
    void updateStatusFields(@Param("id") UUID id,
                            @Param("status") ProcessingStatus status,
                            @Param("errorMessage") String errorMessage,
                            @Param("completedAt") LocalDateTime completedAt);

    /**
     * Find invoice with parties and line items eagerly loaded
     */
    @Query("SELECT i FROM ProcessedTaxInvoiceEntity i " +
           "LEFT JOIN FETCH i.parties " +
           "LEFT JOIN FETCH i.lineItems " +
           "WHERE i.id = :id")
    Optional<ProcessedTaxInvoiceEntity> findByIdWithDetails(@Param("id") UUID id);

    /**
     * Find invoices by status with details
     */
    @Query("SELECT DISTINCT i FROM ProcessedTaxInvoiceEntity i " +
           "LEFT JOIN FETCH i.parties " +
           "LEFT JOIN FETCH i.lineItems " +
           "WHERE i.status = :status")
    List<ProcessedTaxInvoiceEntity> findByStatusWithDetails(@Param("status") ProcessingStatus status);
}
