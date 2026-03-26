package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.processing.domain.model.Address;
import com.wpanther.taxinvoice.processing.domain.model.LineItem;
import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.Party;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.TaxIdentifier;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence.TaxInvoicePartyEntity.PartyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Mapper between domain model and JPA entities
 */
@Component
@Slf4j
public class ProcessedTaxInvoiceMapper {

    /**
     * Convert domain model to JPA entity
     */
    public ProcessedTaxInvoiceEntity toEntity(ProcessedTaxInvoice domain) {
        ProcessedTaxInvoiceEntity entity = ProcessedTaxInvoiceEntity.builder()
            .id(domain.getId().value())
            .sourceInvoiceId(domain.getSourceInvoiceId())
            .invoiceNumber(domain.getInvoiceNumber())
            .issueDate(domain.getIssueDate())
            .dueDate(domain.getDueDate())
            .currency(domain.getCurrency())
            .subtotal(domain.getSubtotal().amount())
            .totalTax(domain.getTotalTax().amount())
            .total(domain.getTotal().amount())
            .originalXml(domain.getOriginalXml())
            .status(domain.getStatus())
            .errorMessage(domain.getErrorMessage())
            .createdAt(domain.getCreatedAt())
            .completedAt(domain.getCompletedAt())
            .parties(new HashSet<>())
            .lineItems(new ArrayList<>())
            .build();

        // Map seller
        TaxInvoicePartyEntity seller = toPartyEntity(domain.getSeller(), PartyType.SELLER);
        entity.addParty(seller);

        // Map buyer
        TaxInvoicePartyEntity buyer = toPartyEntity(domain.getBuyer(), PartyType.BUYER);
        entity.addParty(buyer);

        // Map line items
        int lineNumber = 1;
        for (LineItem item : domain.getItems()) {
            TaxInvoiceLineItemEntity lineItemEntity = toLineItemEntity(item, lineNumber++);
            entity.addLineItem(lineItemEntity);
        }

        return entity;
    }

    /**
     * Convert JPA entity to domain model
     */
    public ProcessedTaxInvoice toDomain(ProcessedTaxInvoiceEntity entity) {
        // Validate entity is not null
        Objects.requireNonNull(entity, "Entity cannot be null");
        Objects.requireNonNull(entity.getParties(), "Entity parties cannot be null");
        Objects.requireNonNull(entity.getLineItems(), "Entity line items cannot be null");

        // Find seller and buyer
        Party seller = null;
        Party buyer = null;

        for (TaxInvoicePartyEntity partyEntity : entity.getParties()) {
            if (partyEntity == null) {
                continue; // Skip null party entities
            }
            Party party = toPartyDomain(partyEntity);
            if (partyEntity.getPartyType() == PartyType.SELLER) {
                seller = party;
            } else if (partyEntity.getPartyType() == PartyType.BUYER) {
                buyer = party;
            }
        }

        // Convert line items
        List<LineItem> items = new ArrayList<>();
        for (TaxInvoiceLineItemEntity itemEntity : entity.getLineItems()) {
            if (itemEntity != null) {
                items.add(toLineItemDomain(itemEntity, entity.getCurrency()));
            }
        }

        if (seller == null) {
            throw new IllegalStateException(
                "No SELLER party found for invoice " + entity.getId());
        }
        if (buyer == null) {
            throw new IllegalStateException(
                "No BUYER party found for invoice " + entity.getId());
        }

        // Warn on absent taxIdentifier — required by Thai e-Tax specification but stored as
        // nullable to support legacy rows that predate the parser's enforcement of the field.
        if (seller.taxIdentifier().isEmpty()) {
            log.warn("Seller party has null taxIdentifier for invoice {} — violates Thai e-Tax "
                + "specification; likely a legacy DB row predating parser enforcement",
                entity.getId());
        }
        if (buyer.taxIdentifier().isEmpty()) {
            log.warn("Buyer party has null taxIdentifier for invoice {} — violates Thai e-Tax "
                + "specification; likely a legacy DB row predating parser enforcement",
                entity.getId());
        }

        // Build domain object
        return ProcessedTaxInvoice.builder()
            .id(TaxInvoiceId.from(entity.getId().toString()))
            .sourceInvoiceId(entity.getSourceInvoiceId())
            .invoiceNumber(entity.getInvoiceNumber())
            .issueDate(entity.getIssueDate())
            .dueDate(entity.getDueDate())
            .seller(seller)
            .buyer(buyer)
            .items(items)
            .currency(entity.getCurrency())
            .originalXml(entity.getOriginalXml())
            .status(entity.getStatus())
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .errorMessage(entity.getErrorMessage())
            .build();
    }

    private TaxInvoicePartyEntity toPartyEntity(Party domain, PartyType partyType) {
        return TaxInvoicePartyEntity.builder()
            .partyType(partyType)
            .name(domain.name())
            .taxId(domain.taxIdentifier().map(TaxIdentifier::value).orElse(null))
            .taxIdScheme(domain.taxIdentifier().map(TaxIdentifier::scheme).orElse(null))
            .streetAddress(domain.address() != null ? domain.address().streetAddress() : null)
            .city(domain.address() != null ? domain.address().city() : null)
            .postalCode(domain.address() != null ? domain.address().postalCode() : null)
            .country(domain.address() != null ? domain.address().country() : null)
            .email(domain.email())
            .build();
    }

    private Party toPartyDomain(TaxInvoicePartyEntity entity) {
        TaxIdentifier taxId = entity.getTaxId() != null
            ? TaxIdentifier.of(entity.getTaxId(), entity.getTaxIdScheme())
            : null;

        // country is NULL when the party had no postal address (optional per Thai e-Tax XSD).
        // Reconstruct a null address to preserve the domain model's null semantics.
        Address address = entity.getCountry() != null
            ? Address.of(entity.getStreetAddress(), entity.getCity(),
                         entity.getPostalCode(), entity.getCountry())
            : null;

        return Party.of(entity.getName(), taxId, address, entity.getEmail());
    }

    private TaxInvoiceLineItemEntity toLineItemEntity(LineItem domain, int lineNumber) {
        return TaxInvoiceLineItemEntity.builder()
            .lineNumber(lineNumber)
            .description(domain.description())
            .quantity(domain.quantity())
            .unitPrice(domain.unitPrice().amount())
            .taxRate(domain.taxRate())
            .lineTotal(domain.getLineTotal().amount())
            .taxAmount(domain.getTaxAmount().amount())
            .build();
    }

    private LineItem toLineItemDomain(TaxInvoiceLineItemEntity entity, String currency) {
        Money unitPrice = Money.of(entity.getUnitPrice(), currency);
        return new LineItem(
            entity.getDescription(),
            entity.getQuantity(),
            unitPrice,
            entity.getTaxRate()
        );
    }
}
