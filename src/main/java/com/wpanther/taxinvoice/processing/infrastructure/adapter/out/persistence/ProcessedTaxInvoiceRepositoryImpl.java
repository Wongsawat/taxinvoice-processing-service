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
import java.util.stream.Collectors;

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
        if (jpaRepository.existsById(id)) {
            // Update only mutable fields — parties and line items never change after creation
            ProcessedTaxInvoiceEntity existing = jpaRepository.getReferenceById(id);
            existing.setStatus(invoice.getStatus());
            existing.setErrorMessage(invoice.getErrorMessage());
            existing.setCompletedAt(invoice.getCompletedAt());
            jpaRepository.save(existing);
        } else {
            // New entity — full mapping
            jpaRepository.save(mapper.toEntity(invoice));
        }

        log.info("Saved processed tax invoice: {} with ID: {}", invoice.getInvoiceNumber(), id);
        return invoice;
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
        return jpaRepository.findByInvoiceNumber(invoiceNumber)
            .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessedTaxInvoice> findByStatus(ProcessingStatus status) {
        List<ProcessedTaxInvoiceEntity> entities = jpaRepository.findByStatusWithDetails(status);
        return entities.stream()
            .map(mapper::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcessedTaxInvoice> findBySourceInvoiceId(String sourceInvoiceId) {
        return jpaRepository.findBySourceInvoiceId(sourceInvoiceId)
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
