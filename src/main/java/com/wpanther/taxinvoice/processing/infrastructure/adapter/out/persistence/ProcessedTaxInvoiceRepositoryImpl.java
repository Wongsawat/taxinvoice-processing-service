package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import com.wpanther.taxinvoice.processing.domain.port.out.ProcessedTaxInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of ProcessedTaxInvoiceRepository using Spring Data JPA
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ProcessedTaxInvoiceRepositoryImpl implements ProcessedTaxInvoiceRepository {

    private final JpaProcessedTaxInvoiceRepository jpaRepository;
    private final ProcessedTaxInvoiceMapper mapper;

    /**
     * Persists a {@link ProcessedTaxInvoice}, using its current status as a zero-cost
     * INSERT vs UPDATE discriminator.
     *
     * <p><b>CONTRACT — callers must honour the following invariant:</b>
     * <ul>
     *   <li>The <em>first</em> call for any given invoice ID <strong>must</strong> pass a
     *       {@link ProcessingStatus#PROCESSING} invoice.  The application service guarantees
     *       this by calling {@code startProcessing()} (PENDING → PROCESSING) before the
     *       initial {@code save()}.  PENDING is therefore never committed to the database.</li>
     *   <li>All <em>subsequent</em> calls for the same ID must pass a non-PROCESSING status
     *       (e.g. COMPLETED, FAILED) — these take the UPDATE path.</li>
     * </ul>
     *
     * <p>Violating the contract by passing a non-PROCESSING invoice that has never been
     * INSERTed is detected by an {@link IllegalStateException} thrown unconditionally
     * (not {@code assert}) so the failure is loud in all environments, including production.
     */
    @Override
    @Transactional
    public ProcessedTaxInvoice save(ProcessedTaxInvoice invoice) {
        log.debug("Saving processed tax invoice: {}", invoice.getInvoiceNumber());

        UUID id = invoice.getId().value();

        ProcessedTaxInvoice result;
        // PROCESSING is the first status ever persisted: the service always calls
        // startProcessing() before the initial save(), so PENDING is never committed
        // to the database. Use this as a zero-cost insert/update discriminator,
        // eliminating the existsById SELECT that would otherwise be needed on every call.
        if (invoice.getStatus() == ProcessingStatus.PROCESSING) {
            // New entity — full mapping. Map the saved entity back to domain so
            // server-side fields (@CreationTimestamp, @Version) are reflected.
            ProcessedTaxInvoiceEntity saved = jpaRepository.save(mapper.toEntity(invoice));
            result = mapper.toDomain(saved);
        } else {
            // Guard: a non-PROCESSING invoice passed to save() must already exist in the
            // database — otherwise the UPDATE below silently affects zero rows (data loss).
            // Throws unconditionally (not assert) so contract violations are caught in
            // production regardless of whether the JVM was started with -ea.
            if (!jpaRepository.existsById(id)) {
                throw new IllegalStateException(
                    "save() called with non-PROCESSING status on unpersisted invoice: " + id
                    + " (status=" + invoice.getStatus() + "). Callers must call startProcessing()"
                    + " and save() once with PROCESSING status before updating to another status.");
            }

            // Existing entity — update only mutable fields via direct UPDATE,
            // avoiding a full SELECT + dirty-check cycle on every state transition.
            jpaRepository.updateStatusFields(
                id, invoice.getStatus(), invoice.getErrorMessage(), invoice.getCompletedAt());
            result = invoice;
        }

        log.info("Saved processed tax invoice: {} with ID: {}", invoice.getInvoiceNumber(), id);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcessedTaxInvoice> findById(TaxInvoiceId id) {
        return jpaRepository.findByIdWithDetails(id.value())
            .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcessedTaxInvoice> findByInvoiceNumber(String invoiceNumber) {
        return jpaRepository.findByInvoiceNumberWithDetails(invoiceNumber)
            .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status) {
        List<ProcessedTaxInvoiceEntity> entities = jpaRepository.findByStatusWithDetails(status);
        return entities.stream()
            .map(mapper::toDomain)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcessedTaxInvoice> findBySourceInvoiceId(String sourceInvoiceId) {
        return jpaRepository.findBySourceInvoiceIdWithDetails(sourceInvoiceId)
            .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public void deleteById(TaxInvoiceId id) {
        log.info("Deleting tax invoice with ID: {}", id);
        jpaRepository.deleteById(id.value());
    }
}
