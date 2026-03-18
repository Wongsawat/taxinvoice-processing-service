package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.processing.domain.model.ProcessingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JPA Entity for ProcessedTaxInvoice aggregate
 */
@Entity
@Table(name = "processed_tax_invoices",
    uniqueConstraints = {
        @UniqueConstraint(name = "idx_tax_invoice_number_unique", columnNames = "invoice_number")
    },
    indexes = {
        @Index(name = "idx_tax_source_invoice_id", columnList = "source_invoice_id"),
        @Index(name = "idx_tax_status", columnList = "status"),
        @Index(name = "idx_tax_issue_date", columnList = "issue_date")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedTaxInvoiceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source_invoice_id", nullable = false, length = 100)
    private String sourceInvoiceId;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 50)
    private String invoiceNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "total_tax", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalTax;

    @Column(name = "total", nullable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @Column(name = "original_xml", nullable = false, columnDefinition = "TEXT")
    private String originalXml;

    // Mutable fields — only these three change after initial insert (PENDING→PROCESSING→COMPLETED)
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProcessingStatus status;

    @Setter
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Setter
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Optimistic locking — guards against concurrent updates from non-saga paths
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    // Relationships
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<TaxInvoicePartyEntity> parties = new HashSet<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    @Builder.Default
    private List<TaxInvoiceLineItemEntity> lineItems = new ArrayList<>();

    // Helper methods for bidirectional relationships
    public void addParty(TaxInvoicePartyEntity party) {
        parties.add(party);
        party.setInvoice(this);
    }

    public void addLineItem(TaxInvoiceLineItemEntity lineItem) {
        lineItems.add(lineItem);
        lineItem.setInvoice(this);
    }
}
