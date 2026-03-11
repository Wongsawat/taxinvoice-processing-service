package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaxInvoicePartyEntity
 */
class TaxInvoicePartyEntityTest {

    @Test
    void testBuilderAndGetters() {
        ProcessedTaxInvoiceEntity invoice = ProcessedTaxInvoiceEntity.builder().build();
        UUID id = UUID.randomUUID();

        TaxInvoicePartyEntity party = TaxInvoicePartyEntity.builder()
            .id(id)
            .invoice(invoice)
            .partyType(TaxInvoicePartyEntity.PartyType.SELLER)
            .name("Test Company")
            .taxId("1234567890")
            .taxIdScheme("VAT")
            .streetAddress("123 Street")
            .city("Bangkok")
            .postalCode("10110")
            .country("TH")
            .email("test@company.com")
            .build();

        assertEquals(id, party.getId());
        assertEquals(TaxInvoicePartyEntity.PartyType.SELLER, party.getPartyType());
        assertEquals("Test Company", party.getName());
        assertEquals("1234567890", party.getTaxId());
        assertEquals("test@company.com", party.getEmail());
    }

    @Test
    void testNoArgsConstructor() {
        TaxInvoicePartyEntity party = new TaxInvoicePartyEntity();
        assertNotNull(party);
        assertNull(party.getId());
    }

    @Test
    void testAllArgsConstructor() {
        UUID id = UUID.randomUUID();
        ProcessedTaxInvoiceEntity invoice = new ProcessedTaxInvoiceEntity();

        TaxInvoicePartyEntity party = new TaxInvoicePartyEntity(
            id, invoice, TaxInvoicePartyEntity.PartyType.BUYER,
            "Buyer Co", "9876543210", "VAT",
            "456 Road", "Chiang Mai", "50000", "TH", "buyer@co.com"
        );

        assertEquals(id, party.getId());
        assertEquals(TaxInvoicePartyEntity.PartyType.BUYER, party.getPartyType());
        assertEquals("Buyer Co", party.getName());
    }
}
