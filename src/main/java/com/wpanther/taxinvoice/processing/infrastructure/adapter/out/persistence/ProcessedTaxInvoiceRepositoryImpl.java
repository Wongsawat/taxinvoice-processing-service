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

    @Override
    @Transactional
    public ProcessedTaxInvoice save(ProcessedTaxInvoice invoice) {
        log.debug("Saving processed tax invoice: {}", invoice.getInvoiceNumber());

        UUID id = invoice.getId().value();

        ProcessedTaxInvoice result;
        if (jpaRepository.existsById(id)) {
            // Entity exists — update only mutable fields via direct UPDATE,
            // avoiding a full SELECT + dirty-check cycle on every state transition.
            jpaRepository.updateStatusFields(
                id, invoice.getStatus(), invoice.getErrorMessage(), invoice.getCompletedAt());
            result = invoice;
        } else {
            // New entity — full mapping. Map the saved entity back to domain so
            // server-side fields (@CreationTimestamp, @Version) are reflected.
            ProcessedTaxInvoiceEntity saved = jpaRepository.save(mapper.toEntity(invoice));
            result = mapper.toDomain(saved);
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
    @Transactional(readOnly = true)
    public boolean existsByInvoiceNumber(String invoiceNumber) {
        return jpaRepository.existsByInvoiceNumber(invoiceNumber);
    }

    @Override
    @Transactional
    public void deleteById(TaxInvoiceId id) {
        log.info("Deleting tax invoice with ID: {}", id);
        jpaRepository.deleteById(id.value());
    }
}
