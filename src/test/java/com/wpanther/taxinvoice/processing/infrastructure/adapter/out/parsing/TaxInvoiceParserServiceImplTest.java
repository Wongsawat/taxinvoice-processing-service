package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.parsing;

import com.wpanther.taxinvoice.processing.domain.model.*;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaxInvoiceParserServiceImpl
 */
class TaxInvoiceParserServiceImplTest {

    private TaxInvoiceParserPort parserService;

    @BeforeEach
    void setUp() {
        parserService = new TaxInvoiceParserServiceImpl();
    }

    @Test
    void constructor_whenJaxbContextFails_throwsIllegalStateException() throws Exception {
        try (MockedStatic<JAXBContext> mockedJaxb = mockStatic(JAXBContext.class)) {
            mockedJaxb.when(() -> JAXBContext.newInstance(anyString()))
                .thenThrow(new JAXBException("Simulated JAXB failure"));
            assertThrows(IllegalStateException.class, () -> new TaxInvoiceParserServiceImpl());
        }
    }

    @Test
    void parse_whenUnmarshalReturnsUnexpectedType_throwsException() throws Exception {
        try (MockedStatic<JAXBContext> mockedJaxb = mockStatic(JAXBContext.class)) {
            JAXBContext mockContext = mock(JAXBContext.class);
            Unmarshaller mockUnmarshaller = mock(Unmarshaller.class);
            mockedJaxb.when(() -> JAXBContext.newInstance(anyString())).thenReturn(mockContext);
            when(mockContext.createUnmarshaller()).thenReturn(mockUnmarshaller);
            when(mockUnmarshaller.unmarshal(any(Reader.class))).thenReturn("unexpected-string-type");

            TaxInvoiceParserServiceImpl service = new TaxInvoiceParserServiceImpl();
            TaxInvoiceParserPort.TaxInvoiceParsingException ex = assertThrows(
                TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> service.parse("<test/>", "test-id")
            );
            assertTrue(ex.getMessage().contains("Unexpected root element"));
        }
    }

    @Test
    void testParseValidTaxInvoice() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: A valid Thai e-Tax tax invoice XML
        String xmlContent = getSampleTaxInvoiceXml();
        String sourceInvoiceId = "intake-12345";

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, sourceInvoiceId);

        // Then: All fields should be correctly parsed
        assertNotNull(invoice);
        assertEquals(sourceInvoiceId, invoice.getSourceInvoiceId());
        assertEquals("TV2025-00001", invoice.getInvoiceNumber());
        assertEquals(LocalDate.of(2025, 1, 15), invoice.getIssueDate());
        assertEquals(LocalDate.of(2025, 2, 14), invoice.getDueDate());
        assertEquals("THB", invoice.getCurrency());
        assertNotNull(invoice.getId());
        assertEquals(xmlContent, invoice.getOriginalXml());
    }

    @Test
    void testParseSellerInformation() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: A valid tax invoice XML
        String xmlContent = getSampleTaxInvoiceXml();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Seller information should be correctly parsed
        Party seller = invoice.getSeller();
        assertNotNull(seller);
        assertEquals("Acme Corporation Ltd.", seller.name());
        assertEquals("1234567890123", seller.taxIdentifier().value());
        assertEquals("VAT", seller.taxIdentifier().scheme());

        Address sellerAddress = seller.address();
        assertNotNull(sellerAddress);
        assertEquals("123 Business Street", sellerAddress.streetAddress());
        assertEquals("Bangkok", sellerAddress.city());
        assertEquals("10110", sellerAddress.postalCode());
        assertEquals("TH", sellerAddress.country());
    }

    @Test
    void testParseBuyerInformation() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: A valid tax invoice XML
        String xmlContent = getSampleTaxInvoiceXml();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Buyer information should be correctly parsed
        Party buyer = invoice.getBuyer();
        assertNotNull(buyer);
        assertEquals("Customer Company Ltd.", buyer.name());
        assertEquals("9876543210987", buyer.taxIdentifier().value());

        Address buyerAddress = buyer.address();
        assertNotNull(buyerAddress);
        assertEquals("456 Customer Road", buyerAddress.streetAddress());
        assertEquals("Chiang Mai", buyerAddress.city());
        assertEquals("50000", buyerAddress.postalCode());
        assertEquals("TH", buyerAddress.country());
    }

    @Test
    void testParseLineItems() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: A valid tax invoice XML with line items
        String xmlContent = getSampleTaxInvoiceXml();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Line items should be correctly parsed
        assertNotNull(invoice.getItems());
        assertEquals(2, invoice.getItems().size());

        // First line item
        LineItem item1 = invoice.getItems().get(0);
        assertEquals("Professional Services - Consulting", item1.description());
        assertEquals(10, item1.quantity());
        assertEquals(Money.of(new BigDecimal("5000.00"), "THB"), item1.unitPrice());
        assertEquals(new BigDecimal("7.00"), item1.taxRate());

        // Second line item
        LineItem item2 = invoice.getItems().get(1);
        assertEquals("Software License", item2.description());
        assertEquals(1, item2.quantity());
        assertEquals(Money.of(new BigDecimal("10000.00"), "THB"), item2.unitPrice());
        assertEquals(new BigDecimal("7.00"), item2.taxRate());
    }

    @Test
    void testCalculateTotals() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: A valid tax invoice XML
        String xmlContent = getSampleTaxInvoiceXml();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Totals should be calculated correctly
        // Subtotal: (10 * 5000) + (1 * 10000) = 60,000
        assertEquals(Money.of(new BigDecimal("60000.00"), "THB"), invoice.getSubtotal());

        // Tax: 60,000 * 0.07 = 4,200
        assertEquals(Money.of(new BigDecimal("4200.00"), "THB"), invoice.getTotalTax());

        // Total: 60,000 + 4,200 = 64,200
        assertEquals(Money.of(new BigDecimal("64200.00"), "THB"), invoice.getTotal());
    }

    @Test
    void testParseTaxInvoiceWithNullXml() {
        // Given: Null XML content
        String xmlContent = null;

        // When/Then: Should throw TaxInvoiceParsingException
        assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
            () -> parserService.parse(xmlContent, "test-123"));
    }

    @Test
    void testParseTaxInvoiceWithEmptyXml() {
        // Given: Empty XML content
        String xmlContent = "";

        // When/Then: Should throw TaxInvoiceParsingException
        assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
            () -> parserService.parse(xmlContent, "test-123"));
    }

    @Test
    void testParseTaxInvoiceWithInvalidXml() {
        // Given: Invalid XML content
        String xmlContent = "<invalid>Not a valid tax invoice</invalid>";

        // When/Then: Should throw TaxInvoiceParsingException
        assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
            () -> parserService.parse(xmlContent, "test-123"));
    }

    @Test
    void testParseTaxInvoiceWithMissingInvoiceNumber() {
        // Given: XML without tax invoice number
        String xmlContent = getTaxInvoiceXmlWithoutInvoiceNumber();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Tax invoice number"));
    }

    @Test
    void testParseTaxInvoiceWithMissingLineItems() {
        // Given: XML without line items
        String xmlContent = getTaxInvoiceXmlWithoutLineItems();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("line item"));
    }

    @Test
    void testParseTaxInvoiceWithMissingDueDate() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: XML without due date (should default to issue date + 30 days)
        String xmlContent = getTaxInvoiceXmlWithoutDueDate();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Due date should be issue date + 30 days
        assertNotNull(invoice);
        assertEquals(LocalDate.of(2025, 1, 15), invoice.getIssueDate());
        assertEquals(LocalDate.of(2025, 2, 14), invoice.getDueDate());
    }

    @Test
    void testParseTaxInvoiceWithMinimalAddress() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: XML with minimal address (only country required)
        String xmlContent = getTaxInvoiceXmlWithMinimalAddress();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Address should have only country
        assertNotNull(invoice);
        Party seller = invoice.getSeller();
        assertNotNull(seller.address());
        assertEquals("TH", seller.address().country());
        assertNull(seller.address().streetAddress());
        assertNull(seller.address().city());
        assertNull(seller.address().postalCode());
    }

    @Test
    void testParseTaxInvoiceWithTaxIdNoScheme() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: XML with tax ID but no scheme (should default to "VAT")
        String xmlContent = getTaxInvoiceXmlWithTaxIdNoScheme();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Tax scheme should default to "VAT"
        assertNotNull(invoice);
        assertEquals("VAT", invoice.getSeller().taxIdentifier().scheme());
        assertEquals("1234567890123", invoice.getSeller().taxIdentifier().value());
    }

    @Test
    void testParseTaxInvoiceWithMissingIssueDate() {
        // Given: XML without issue date
        String xmlContent = getTaxInvoiceXmlWithoutIssueDate();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Issue date"));
    }

    @Test
    void testParseTaxInvoiceWithMissingSeller() {
        // Given: XML without seller information
        String xmlContent = getTaxInvoiceXmlWithoutSeller();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Seller"));
    }

    @Test
    void testParseTaxInvoiceWithMissingBuyer() {
        // Given: XML without buyer information
        String xmlContent = getTaxInvoiceXmlWithoutBuyer();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Buyer"));
    }

    @Test
    void testParseTaxInvoiceWithInvalidCurrency() {
        // Given: XML with invalid currency code (not 3 characters)
        String xmlContent = getTaxInvoiceXmlWithInvalidCurrency();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("currency"));
    }

    @Test
    void testParseTaxInvoiceWithMissingCurrency() {
        // Given: XML without currency
        String xmlContent = getTaxInvoiceXmlWithoutCurrency();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("currency"));
    }

    @Test
    void testParseTaxInvoiceWithLineItemMissingTax() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        // Given: XML with line item without tax info (should default to 0%)
        String xmlContent = getTaxInvoiceXmlWithLineItemNoTax();

        // When: Parsing the XML
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-123");

        // Then: Tax rate should be zero
        assertNotNull(invoice);
        assertEquals(1, invoice.getItems().size());
        assertEquals(BigDecimal.ZERO, invoice.getItems().get(0).taxRate());
    }

    @Test
    void testParseTaxInvoiceWithMissingSellerName() {
        // Given: XML without seller name
        String xmlContent = getTaxInvoiceXmlWithoutSellerName();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Seller name"));
    }

    @Test
    void testParseTaxInvoiceWithMissingSellerTaxId() {
        // Given: XML without seller tax ID
        String xmlContent = getTaxInvoiceXmlWithoutSellerTaxId();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Seller tax"));
    }

    @Test
    void testParseTaxInvoiceWithMissingSellerCountry() {
        // Given: XML without seller country
        String xmlContent = getTaxInvoiceXmlWithoutSellerCountry();

        // When/Then: Should throw TaxInvoiceParsingException
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("Seller country"));
    }

    @Test
    void testParseTaxInvoiceWithMissingExchangedDocument() {
        String xmlContent = getTaxInvoiceXmlWithoutExchangedDocument();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().contains("ExchangedDocument"));
    }

    @Test
    void testParseTaxInvoiceWithMissingSupplyChainTradeTransaction() {
        String xmlContent = getTaxInvoiceXmlWithoutSupplyChainTradeTransaction();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().contains("SupplyChainTradeTransaction"));
    }

    @Test
    void testParseTaxInvoiceWithSellerEmail() throws TaxInvoiceParserPort.TaxInvoiceParsingException {
        String xmlContent = getTaxInvoiceXmlWithSellerEmail();
        ProcessedTaxInvoice invoice = parserService.parse(xmlContent, "test-email");
        assertEquals("seller@acme.com", invoice.getSeller().email());
    }

    @Test
    void testParseTaxInvoiceWithMissingSellerTaxRegistration() {
        String xmlContent = getTaxInvoiceXmlWithoutSellerTaxRegistration();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().contains("tax registration"));
    }

    @Test
    void testParseTaxInvoiceWithMissingSellerAddress() {
        String xmlContent = getTaxInvoiceXmlWithoutSellerAddress();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().contains("address"));
    }

    @Test
    void testParseTaxInvoiceWithLineItemMissingProductName() {
        String xmlContent = getTaxInvoiceXmlWithLineItemMissingProduct();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().toLowerCase().contains("line item"));
    }

    @Test
    void testParseTaxInvoiceWithLineItemMissingDelivery() {
        String xmlContent = getTaxInvoiceXmlWithLineItemMissingDelivery();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().toLowerCase().contains("line item"));
    }

    @Test
    void testParseTaxInvoiceWithFractionalQuantity() {
        String xmlContent = getTaxInvoiceXmlWithFractionalQuantity();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().toLowerCase().contains("whole number") || exception.getMessage().toLowerCase().contains("line item"));
    }

    @Test
    void testParseTaxInvoiceWithLineItemMissingAgreement() {
        String xmlContent = getTaxInvoiceXmlWithLineItemMissingAgreement();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().toLowerCase().contains("line item"));
    }

    @Test
    void testParseTaxInvoiceWithLineItemEmptyChargeAmount() {
        String xmlContent = getTaxInvoiceXmlWithLineItemEmptyChargeAmount();
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));
        assertTrue(exception.getMessage().toLowerCase().contains("line item"));
    }

    @Test
    void testParseTaxInvoiceWithSellerTaxRegistrationNoId() {
        // Given: XML where SpecifiedTaxRegistration exists but has no ID child element
        // This triggers the taxReg.getID() == null check
        String xmlContent = getTaxInvoiceXmlWithSellerTaxRegistrationNoId();

        // When/Then: Should throw TaxInvoiceParsingException about missing tax ID
        TaxInvoiceParserPort.TaxInvoiceParsingException exception =
            assertThrows(TaxInvoiceParserPort.TaxInvoiceParsingException.class,
                () -> parserService.parse(xmlContent, "test-123"));

        assertTrue(exception.getMessage().contains("tax ID") || exception.getMessage().contains("tax registration"));
    }

    /**
     * Sample Thai e-Tax tax invoice XML for testing
     */
    private String getSampleTaxInvoiceXml() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16"
                xmlns:qdt="urn:etda:uncefact:data:standard:QualifiedDataType:1">

              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>

              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>

              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Acme Corporation Ltd.</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:LineOne>123 Business Street</ram:LineOne>
                      <ram:CityName>Bangkok</ram:CityName>
                      <ram:PostcodeCode>10110</ram:PostcodeCode>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID schemeID="VAT">1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Customer Company Ltd.</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:LineOne>456 Customer Road</ram:LineOne>
                      <ram:CityName>Chiang Mai</ram:CityName>
                      <ram:PostcodeCode>50000</ram:PostcodeCode>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>

                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                  <ram:SpecifiedTradePaymentTerms>
                    <ram:DueDateDateTime>2025-02-14T00:00:00</ram:DueDateDateTime>
                  </ram:SpecifiedTradePaymentTerms>
                </ram:ApplicableHeaderTradeSettlement>

                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>1</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Professional Services - Consulting</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>5000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="HUR">10</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                  <ram:SpecifiedLineTradeSettlement>
                    <ram:ApplicableTradeTax>
                      <ram:TypeCode>VAT</ram:TypeCode>
                      <ram:CalculatedRate>7.00</ram:CalculatedRate>
                    </ram:ApplicableTradeTax>
                  </ram:SpecifiedLineTradeSettlement>
                </ram:IncludedSupplyChainTradeLineItem>

                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>2</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Software License</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>10000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                  <ram:SpecifiedLineTradeSettlement>
                    <ram:ApplicableTradeTax>
                      <ram:TypeCode>VAT</ram:TypeCode>
                      <ram:CalculatedRate>7.00</ram:CalculatedRate>
                    </ram:ApplicableTradeTax>
                  </ram:SpecifiedLineTradeSettlement>
                </ram:IncludedSupplyChainTradeLineItem>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    /**
     * Tax Invoice XML without invoice number
     */
    private String getTaxInvoiceXmlWithoutInvoiceNumber() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">

              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>

              <rsm:ExchangedDocument>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>

              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    /**
     * Tax Invoice XML without line items
     */
    private String getTaxInvoiceXmlWithoutLineItems() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">

              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>

              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>

              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutDueDate() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>1</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Test Product</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                  <ram:SpecifiedLineTradeSettlement>
                    <ram:ApplicableTradeTax>
                      <ram:TypeCode>VAT</ram:TypeCode>
                      <ram:CalculatedRate>7.00</ram:CalculatedRate>
                    </ram:ApplicableTradeTax>
                  </ram:SpecifiedLineTradeSettlement>
                </ram:IncludedSupplyChainTradeLineItem>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithMinimalAddress() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                  <ram:SpecifiedTradePaymentTerms>
                    <ram:DueDateDateTime>2025-02-14T00:00:00</ram:DueDateDateTime>
                  </ram:SpecifiedTradePaymentTerms>
                </ram:ApplicableHeaderTradeSettlement>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>1</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Test Product</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                  <ram:SpecifiedLineTradeSettlement>
                    <ram:ApplicableTradeTax>
                      <ram:TypeCode>VAT</ram:TypeCode>
                      <ram:CalculatedRate>7.00</ram:CalculatedRate>
                    </ram:ApplicableTradeTax>
                  </ram:SpecifiedLineTradeSettlement>
                </ram:IncludedSupplyChainTradeLineItem>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithTaxIdNoScheme() {
        return getSampleTaxInvoiceXml();  // Our sample already has one without scheme for buyer
    }

    private String getTaxInvoiceXmlWithoutIssueDate() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutSeller() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutBuyer() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithInvalidCurrency() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>INVALID</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>1</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Test Product</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                </ram:IncludedSupplyChainTradeLineItem>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutCurrency() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                </ram:ApplicableHeaderTradeSettlement>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>1</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Test Product</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                </ram:IncludedSupplyChainTradeLineItem>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithLineItemNoTax() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                  <ram:SpecifiedTradePaymentTerms>
                    <ram:DueDateDateTime>2025-02-14T00:00:00</ram:DueDateDateTime>
                  </ram:SpecifiedTradePaymentTerms>
                </ram:ApplicableHeaderTradeSettlement>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument>
                    <ram:LineID>1</ram:LineID>
                  </ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct>
                    <ram:Name>Test Product</ram:Name>
                  </ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:GrossPriceProductTradePrice>
                      <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                    </ram:GrossPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                </ram:IncludedSupplyChainTradeLineItem>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutSellerName() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutSellerTaxId() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private String getTaxInvoiceXmlWithoutSellerCountry() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda.or.th:taxinvoice:2p1</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TV2025-00001</ram:ID>
                <ram:TypeCode>388</ram:TypeCode>
                <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Test Seller</ram:Name>
                    <ram:PostalTradeAddress>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>1234567890123</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty>
                    <ram:Name>Test Buyer</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:CountryID>TH</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration>
                      <ram:ID>9876543210987</ram:ID>
                    </ram:SpecifiedTaxRegistration>
                  </ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    private static final String NS_HEADER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rsm:TaxInvoice_CrossIndustryInvoice
            xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
            xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2"
            xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:16"
            xmlns:qdt="urn:etda:uncefact:data:standard:QualifiedDataType:1">
        """;

    private static final String STANDARD_SELLER = """
          <ram:SellerTradeParty>
            <ram:Name>Acme Corporation Ltd.</ram:Name>
            <ram:PostalTradeAddress>
              <ram:LineOne>123 Business Street</ram:LineOne>
              <ram:CityName>Bangkok</ram:CityName>
              <ram:PostcodeCode>10110</ram:PostcodeCode>
              <ram:CountryID>TH</ram:CountryID>
            </ram:PostalTradeAddress>
            <ram:SpecifiedTaxRegistration>
              <ram:ID schemeID="VAT">1234567890123</ram:ID>
            </ram:SpecifiedTaxRegistration>
          </ram:SellerTradeParty>
        """;

    private static final String STANDARD_BUYER = """
          <ram:BuyerTradeParty>
            <ram:Name>Customer Company Ltd.</ram:Name>
            <ram:PostalTradeAddress>
              <ram:LineOne>456 Customer Road</ram:LineOne>
              <ram:CityName>Chiang Mai</ram:CityName>
              <ram:PostcodeCode>50000</ram:PostcodeCode>
              <ram:CountryID>TH</ram:CountryID>
            </ram:PostalTradeAddress>
            <ram:SpecifiedTaxRegistration>
              <ram:ID>9876543210987</ram:ID>
            </ram:SpecifiedTaxRegistration>
          </ram:BuyerTradeParty>
        """;

    private static final String STANDARD_SETTLEMENT = """
          <ram:ApplicableHeaderTradeSettlement>
            <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>
          </ram:ApplicableHeaderTradeSettlement>
        """;

    private static final String STANDARD_LINE_ITEM = """
          <ram:IncludedSupplyChainTradeLineItem>
            <ram:SpecifiedTradeProduct>
              <ram:Name>Professional Services</ram:Name>
            </ram:SpecifiedTradeProduct>
            <ram:SpecifiedLineTradeAgreement>
              <ram:GrossPriceProductTradePrice>
                <ram:ChargeAmount>1000.00</ram:ChargeAmount>
              </ram:GrossPriceProductTradePrice>
            </ram:SpecifiedLineTradeAgreement>
            <ram:SpecifiedLineTradeDelivery>
              <ram:BilledQuantity unitCode="C62">2</ram:BilledQuantity>
            </ram:SpecifiedLineTradeDelivery>
          </ram:IncludedSupplyChainTradeLineItem>
        """;

    private String buildXml(String exchangedDocument, String supplyChain) {
        return NS_HEADER + (exchangedDocument != null ? exchangedDocument : "") +
               (supplyChain != null ? supplyChain : "") +
               "</rsm:TaxInvoice_CrossIndustryInvoice>";
    }

    private String standardExchangedDocument() {
        return """
          <rsm:ExchangedDocument>
            <ram:ID>TV2025-TEST</ram:ID>
            <ram:IssueDateTime>2025-01-15T00:00:00</ram:IssueDateTime>
          </rsm:ExchangedDocument>
          """;
    }

    private String standardSupplyChain(String sellerOverride) {
        return """
          <rsm:SupplyChainTradeTransaction>
            <ram:ApplicableHeaderTradeAgreement>
          """ + (sellerOverride != null ? sellerOverride : STANDARD_SELLER) + STANDARD_BUYER + """
            </ram:ApplicableHeaderTradeAgreement>
          """ + STANDARD_SETTLEMENT + STANDARD_LINE_ITEM + """
          </rsm:SupplyChainTradeTransaction>
          """;
    }

    private String standardSupplyChainWithLineItem(String lineItemOverride) {
        return """
          <rsm:SupplyChainTradeTransaction>
            <ram:ApplicableHeaderTradeAgreement>
          """ + STANDARD_SELLER + STANDARD_BUYER + """
            </ram:ApplicableHeaderTradeAgreement>
          """ + STANDARD_SETTLEMENT + lineItemOverride + """
          </rsm:SupplyChainTradeTransaction>
          """;
    }

    private String getTaxInvoiceXmlWithoutExchangedDocument() {
        return buildXml(null, standardSupplyChain(null));
    }

    private String getTaxInvoiceXmlWithoutSupplyChainTradeTransaction() {
        return buildXml(standardExchangedDocument(), null);
    }

    private String getTaxInvoiceXmlWithSellerEmail() {
        String sellerWithEmail = """
            <ram:SellerTradeParty>
              <ram:Name>Acme Corporation Ltd.</ram:Name>
              <ram:PostalTradeAddress>
                <ram:LineOne>123 Business Street</ram:LineOne>
                <ram:CityName>Bangkok</ram:CityName>
                <ram:PostcodeCode>10110</ram:PostcodeCode>
                <ram:CountryID>TH</ram:CountryID>
              </ram:PostalTradeAddress>
              <ram:SpecifiedTaxRegistration>
                <ram:ID schemeID="VAT">1234567890123</ram:ID>
              </ram:SpecifiedTaxRegistration>
              <ram:DefinedTradeContact>
                <ram:EmailURIUniversalCommunication>
                  <ram:URIID>seller@acme.com</ram:URIID>
                </ram:EmailURIUniversalCommunication>
              </ram:DefinedTradeContact>
            </ram:SellerTradeParty>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChain(sellerWithEmail));
    }

    private String getTaxInvoiceXmlWithoutSellerTaxRegistration() {
        String sellerNoReg = """
            <ram:SellerTradeParty>
              <ram:Name>Acme Corporation Ltd.</ram:Name>
              <ram:PostalTradeAddress>
                <ram:LineOne>123 Business Street</ram:LineOne>
                <ram:CityName>Bangkok</ram:CityName>
                <ram:PostcodeCode>10110</ram:PostcodeCode>
                <ram:CountryID>TH</ram:CountryID>
              </ram:PostalTradeAddress>
            </ram:SellerTradeParty>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChain(sellerNoReg));
    }

    private String getTaxInvoiceXmlWithoutSellerAddress() {
        String sellerNoAddress = """
            <ram:SellerTradeParty>
              <ram:Name>Acme Corporation Ltd.</ram:Name>
              <ram:SpecifiedTaxRegistration>
                <ram:ID schemeID="VAT">1234567890123</ram:ID>
              </ram:SpecifiedTaxRegistration>
            </ram:SellerTradeParty>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChain(sellerNoAddress));
    }

    private String getTaxInvoiceXmlWithLineItemMissingProduct() {
        String item = """
            <ram:IncludedSupplyChainTradeLineItem>
              <ram:SpecifiedLineTradeAgreement>
                <ram:GrossPriceProductTradePrice>
                  <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                </ram:GrossPriceProductTradePrice>
              </ram:SpecifiedLineTradeAgreement>
              <ram:SpecifiedLineTradeDelivery>
                <ram:BilledQuantity unitCode="C62">2</ram:BilledQuantity>
              </ram:SpecifiedLineTradeDelivery>
            </ram:IncludedSupplyChainTradeLineItem>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChainWithLineItem(item));
    }

    private String getTaxInvoiceXmlWithLineItemMissingDelivery() {
        String item = """
            <ram:IncludedSupplyChainTradeLineItem>
              <ram:SpecifiedTradeProduct>
                <ram:Name>Professional Services</ram:Name>
              </ram:SpecifiedTradeProduct>
              <ram:SpecifiedLineTradeAgreement>
                <ram:GrossPriceProductTradePrice>
                  <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                </ram:GrossPriceProductTradePrice>
              </ram:SpecifiedLineTradeAgreement>
            </ram:IncludedSupplyChainTradeLineItem>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChainWithLineItem(item));
    }

    private String getTaxInvoiceXmlWithFractionalQuantity() {
        String item = """
            <ram:IncludedSupplyChainTradeLineItem>
              <ram:SpecifiedTradeProduct>
                <ram:Name>Professional Services</ram:Name>
              </ram:SpecifiedTradeProduct>
              <ram:SpecifiedLineTradeAgreement>
                <ram:GrossPriceProductTradePrice>
                  <ram:ChargeAmount>1000.00</ram:ChargeAmount>
                </ram:GrossPriceProductTradePrice>
              </ram:SpecifiedLineTradeAgreement>
              <ram:SpecifiedLineTradeDelivery>
                <ram:BilledQuantity unitCode="C62">1.5</ram:BilledQuantity>
              </ram:SpecifiedLineTradeDelivery>
            </ram:IncludedSupplyChainTradeLineItem>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChainWithLineItem(item));
    }

    private String getTaxInvoiceXmlWithLineItemMissingAgreement() {
        String item = """
            <ram:IncludedSupplyChainTradeLineItem>
              <ram:SpecifiedTradeProduct>
                <ram:Name>Professional Services</ram:Name>
              </ram:SpecifiedTradeProduct>
              <ram:SpecifiedLineTradeDelivery>
                <ram:BilledQuantity unitCode="C62">2</ram:BilledQuantity>
              </ram:SpecifiedLineTradeDelivery>
            </ram:IncludedSupplyChainTradeLineItem>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChainWithLineItem(item));
    }

    private String getTaxInvoiceXmlWithLineItemEmptyChargeAmount() {
        String item = """
            <ram:IncludedSupplyChainTradeLineItem>
              <ram:SpecifiedTradeProduct>
                <ram:Name>Professional Services</ram:Name>
              </ram:SpecifiedTradeProduct>
              <ram:SpecifiedLineTradeAgreement>
                <ram:GrossPriceProductTradePrice>
                </ram:GrossPriceProductTradePrice>
              </ram:SpecifiedLineTradeAgreement>
              <ram:SpecifiedLineTradeDelivery>
                <ram:BilledQuantity unitCode="C62">2</ram:BilledQuantity>
              </ram:SpecifiedLineTradeDelivery>
            </ram:IncludedSupplyChainTradeLineItem>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChainWithLineItem(item));
    }

    private String getTaxInvoiceXmlWithSellerTaxRegistrationNoId() {
        // SpecifiedTaxRegistration present but no ID element — triggers taxReg.getID() == null
        String sellerNoId = """
            <ram:SellerTradeParty>
              <ram:Name>Acme Corporation Ltd.</ram:Name>
              <ram:PostalTradeAddress>
                <ram:LineOne>123 Business Street</ram:LineOne>
                <ram:CityName>Bangkok</ram:CityName>
                <ram:PostcodeCode>10110</ram:PostcodeCode>
                <ram:CountryID>TH</ram:CountryID>
              </ram:PostalTradeAddress>
              <ram:SpecifiedTaxRegistration>
              </ram:SpecifiedTaxRegistration>
            </ram:SellerTradeParty>
            """;
        return buildXml(standardExchangedDocument(), standardSupplyChain(sellerNoId));
    }
}
