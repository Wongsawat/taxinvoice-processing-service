package com.wpanther.taxinvoice.processing.infrastructure.adapter.out.parsing;

import com.wpanther.taxinvoice.processing.domain.model.Address;
import com.wpanther.taxinvoice.processing.domain.model.LineItem;
import com.wpanther.taxinvoice.processing.domain.model.Money;
import com.wpanther.taxinvoice.processing.domain.model.Party;
import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;
import com.wpanther.taxinvoice.processing.domain.model.TaxIdentifier;
import com.wpanther.taxinvoice.processing.domain.model.TaxInvoiceId;
import com.wpanther.taxinvoice.processing.domain.port.out.TaxInvoiceParserPort;
import com.wpanther.etax.generated.taxinvoice.ram.ExchangedDocumentType;
import com.wpanther.etax.generated.taxinvoice.ram.HeaderTradeAgreementType;
import com.wpanther.etax.generated.taxinvoice.ram.HeaderTradeSettlementType;
import com.wpanther.etax.generated.taxinvoice.ram.LineTradeAgreementType;
import com.wpanther.etax.generated.taxinvoice.ram.LineTradeDeliveryType;
import com.wpanther.etax.generated.taxinvoice.ram.LineTradeSettlementType;
import com.wpanther.etax.generated.taxinvoice.ram.SupplyChainTradeLineItemType;
import com.wpanther.etax.generated.taxinvoice.ram.SupplyChainTradeTransactionType;
import com.wpanther.etax.generated.taxinvoice.ram.TradeAddressType;
import com.wpanther.etax.generated.taxinvoice.ram.TradeContactType;
import com.wpanther.etax.generated.taxinvoice.ram.TradePartyType;
import com.wpanther.etax.generated.taxinvoice.ram.TradePaymentTermsType;
import com.wpanther.etax.generated.taxinvoice.ram.TradePriceType;
import com.wpanther.etax.generated.taxinvoice.ram.TradeProductType;
import com.wpanther.etax.generated.taxinvoice.ram.TradeTaxType;
import com.wpanther.etax.generated.taxinvoice.ram.TaxRegistrationType;
import com.wpanther.etax.generated.taxinvoice.rsm.TaxInvoice_CrossIndustryInvoiceType;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of TaxInvoiceParserService that uses teda library's JAXB classes
 * to parse Thai e-Tax tax invoice XML.
 */
@Slf4j
@Service
public class TaxInvoiceParserServiceImpl implements TaxInvoiceParserPort {

    private static final ZoneId TH_ZONE = ZoneId.of("Asia/Bangkok");

    private final JAXBContext jaxbContext;
    private final SAXParserFactory saxParserFactory;

    public TaxInvoiceParserServiceImpl() {
        try {
            // Initialize JAXB context with the implementation package
            // The teda library uses interface/implementation pattern with a custom JAXBContextFactory
            // We need to use the package path to let the factory handle the context creation
            // CRITICAL: Uses taxinvoice packages, NOT invoice packages
            String contextPath = "com.wpanther.etax.generated.taxinvoice.rsm.impl" +
                               ":com.wpanther.etax.generated.taxinvoice.ram.impl" +
                               ":com.wpanther.etax.generated.common.qdt.impl" +
                               ":com.wpanther.etax.generated.common.udt.impl";
            this.jaxbContext = JAXBContext.newInstance(contextPath);
            log.info("JAXB context initialized successfully for Thai e-Tax tax invoice parsing");
        } catch (JAXBException e) {
            log.error("Failed to initialize JAXB context", e);
            throw new IllegalStateException("Failed to initialize XML parser", e);
        }

        try {
            // Secure SAX parser factory — disables external entities and DOCTYPE to prevent XXE/XML-bomb attacks
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            this.saxParserFactory = factory;
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IllegalStateException("Failed to initialize secure SAX parser factory", e);
        }
    }

    @Override
    public ProcessedTaxInvoice parse(String xmlContent, String sourceInvoiceId)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        log.debug("Starting XML parsing for source invoice ID: {}", sourceInvoiceId);

        try {
            // Step 1: Unmarshal XML to JAXB object
            TaxInvoice_CrossIndustryInvoiceType jaxbInvoice = unmarshalXml(xmlContent);

            // Step 2: Extract invoice components
            ExchangedDocumentType document = jaxbInvoice.getExchangedDocument();
            if (document == null) {
                throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Tax invoice XML missing required ExchangedDocument element");
            }

            SupplyChainTradeTransactionType transaction = jaxbInvoice.getSupplyChainTradeTransaction();
            if (transaction == null) {
                throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Tax invoice XML missing required SupplyChainTradeTransaction element");
            }

            // Step 3: Map to domain model
            LocalDate issueDate = extractIssueDate(document);
            String currency = extractCurrency(transaction);

            ProcessedTaxInvoice invoice = ProcessedTaxInvoice.builder()
                .id(TaxInvoiceId.generate())
                .sourceInvoiceId(sourceInvoiceId)
                .invoiceNumber(extractInvoiceNumber(document))
                .issueDate(issueDate)
                .dueDate(extractDueDate(transaction, issueDate))
                .seller(extractSeller(transaction))
                .buyer(extractBuyer(transaction))
                .items(extractLineItems(transaction, currency))
                .currency(currency)
                .originalXml(xmlContent)
                .build();

            log.info("Successfully parsed tax invoice {} with {} line items",
                invoice.getInvoiceNumber(), invoice.getItems().size());

            return invoice;

        } catch (TaxInvoiceParserPort.TaxInvoiceParsingException e) {
            log.error("Failed to parse tax invoice XML for source ID {}: {}",
                sourceInvoiceId, e.getMessage());
            throw e;
        }
    }

    /**
     * Unmarshal XML string to JAXB object
     */
    private TaxInvoice_CrossIndustryInvoiceType unmarshalXml(String xmlContent)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        if (xmlContent == null || xmlContent.isBlank()) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("XML content is null or empty");
        }

        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();

            Object result;
            try (StringReader reader = new StringReader(xmlContent)) {
                org.xml.sax.XMLReader xmlReader = saxParserFactory.newSAXParser().getXMLReader();
                // Defense-in-depth: explicitly re-apply FEATURE_SECURE_PROCESSING on the
                // XMLReader at point of use, not just on the factory. This caps entity
                // expansion (JDK limits to 64,000 expansions) as a second guard against
                // billion-laughs attacks in case the factory-level feature was not fully
                // propagated by the underlying parser implementation.
                xmlReader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                SAXSource saxSource = new SAXSource(xmlReader, new InputSource(reader));
                result = unmarshaller.unmarshal(saxSource);
            }

            // Handle JAXBElement wrapper (common when no @XmlRootElement annotation)
            if (result instanceof jakarta.xml.bind.JAXBElement) {
                jakarta.xml.bind.JAXBElement<?> jaxbElement = (jakarta.xml.bind.JAXBElement<?>) result;
                result = jaxbElement.getValue();
            }

            if (!(result instanceof TaxInvoice_CrossIndustryInvoiceType)) {
                throw new TaxInvoiceParserPort.TaxInvoiceParsingException(
                    "Unexpected root element: " + result.getClass().getName()
                );
            }

            return (TaxInvoice_CrossIndustryInvoiceType) result;

        } catch (JAXBException | org.xml.sax.SAXException | ParserConfigurationException e) {
            log.error("JAXB unmarshalling failed", e);
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Failed to parse XML: " + e.getMessage(), e);
        }
    }

    /**
     * Extract invoice number from document
     */
    private String extractInvoiceNumber(ExchangedDocumentType document)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        if (document.getID() == null || document.getID().getValue() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Tax invoice number (ID) is missing");
        }

        return document.getID().getValue();
    }

    /**
     * Extract issue date from document
     */
    private LocalDate extractIssueDate(ExchangedDocumentType document)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        XMLGregorianCalendar issueDateTime = document.getIssueDateTime();
        if (issueDateTime == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Issue date/time is missing");
        }

        return convertXMLGregorianCalendarToLocalDate(issueDateTime);
    }

    /**
     * Extract due date from transaction settlement
     */
    private LocalDate extractDueDate(SupplyChainTradeTransactionType transaction, LocalDate issueDate)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        HeaderTradeSettlementType settlement = transaction.getApplicableHeaderTradeSettlement();

        // Due date might be in payment terms
        List<TradePaymentTermsType> paymentTerms = settlement.getSpecifiedTradePaymentTerms();
        if (paymentTerms != null && !paymentTerms.isEmpty()) {
            TradePaymentTermsType terms = paymentTerms.get(0);
            XMLGregorianCalendar dueDateTime = terms.getDueDateDateTime();
            if (dueDateTime != null) {
                return convertXMLGregorianCalendarToLocalDate(dueDateTime);
            }
        }

        // Default to issue date + 30 days if not specified
        log.warn("Due date not found in XML, defaulting to issue date + 30 days");
        return issueDate.plusDays(30);
    }

    /**
     * Extract seller party information
     */
    private Party extractSeller(SupplyChainTradeTransactionType transaction)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        HeaderTradeAgreementType agreement = transaction.getApplicableHeaderTradeAgreement();
        if (agreement == null || agreement.getSellerTradeParty() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Seller information is missing");
        }

        return mapParty(agreement.getSellerTradeParty(), "Seller");
    }

    /**
     * Extract buyer party information
     */
    private Party extractBuyer(SupplyChainTradeTransactionType transaction)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        HeaderTradeAgreementType agreement = transaction.getApplicableHeaderTradeAgreement();
        if (agreement == null || agreement.getBuyerTradeParty() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Buyer information is missing");
        }

        return mapParty(agreement.getBuyerTradeParty(), "Buyer");
    }

    /**
     * Map JAXB trade party to domain Party
     */
    private Party mapParty(TradePartyType jaxbParty, String partyType)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        // Extract name
        String name = Optional.ofNullable(jaxbParty.getName())
            .map(n -> n.getValue())
            .orElseThrow(() -> new TaxInvoiceParsingException(partyType + " name is missing"));

        // Extract tax identifier
        TaxIdentifier taxIdentifier = extractTaxIdentifier(jaxbParty, partyType);

        // Extract address
        Address address = extractAddress(jaxbParty, partyType);

        // Extract email (optional)
        String email = null;
        List<TradeContactType> contacts = jaxbParty.getDefinedTradeContact();
        if (contacts != null && !contacts.isEmpty()) {
            TradeContactType contact = contacts.get(0);
            if (contact.getEmailURIUniversalCommunication() != null &&
                contact.getEmailURIUniversalCommunication().getURIID() != null) {
                email = contact.getEmailURIUniversalCommunication().getURIID().getValue();
            }
        }

        return Party.of(name, taxIdentifier, address, email);
    }

    /**
     * Extract tax identifier from party
     */
    private TaxIdentifier extractTaxIdentifier(TradePartyType jaxbParty, String partyType)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        TaxRegistrationType taxReg = jaxbParty.getSpecifiedTaxRegistration();
        if (taxReg == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(partyType + " tax registration is missing");
        }

        if (taxReg.getID() == null || taxReg.getID().getValue() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(partyType + " tax ID is missing");
        }

        String taxId = taxReg.getID().getValue();
        String scheme = Optional.ofNullable(taxReg.getID().getSchemeID())
            .orElse("VAT");

        return TaxIdentifier.of(taxId, scheme);
    }

    /**
     * Extract address from party
     */
    private Address extractAddress(TradePartyType jaxbParty, String partyType)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        TradeAddressType jaxbAddress = jaxbParty.getPostalTradeAddress();
        if (jaxbAddress == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(partyType + " address is missing");
        }

        // Build address (some fields may be optional)
        String streetAddress = Optional.ofNullable(jaxbAddress.getLineOne())
            .map(line -> line.getValue())
            .orElse(null);

        String city = Optional.ofNullable(jaxbAddress.getCityName())
            .map(name -> name.getValue())
            .orElse(null);

        String postalCode = Optional.ofNullable(jaxbAddress.getPostcodeCode())
            .map(code -> code.getValue())
            .orElse(null);

        String country = null;
        if (jaxbAddress.getCountryID() != null && jaxbAddress.getCountryID().getValue() != null) {
            country = jaxbAddress.getCountryID().getValue().value();
        }
        if (country == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(partyType + " country is missing");
        }

        return Address.of(streetAddress, city, postalCode, country);
    }

    /**
     * Extract line items
     */
    private List<LineItem> extractLineItems(SupplyChainTradeTransactionType transaction, String currency)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        List<SupplyChainTradeLineItemType> jaxbItems =
            transaction.getIncludedSupplyChainTradeLineItem();

        if (jaxbItems == null || jaxbItems.isEmpty()) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Tax invoice must have at least one line item");
        }

        List<LineItem> items = new ArrayList<>();

        for (SupplyChainTradeLineItemType jaxbItem : jaxbItems) {
            items.add(mapLineItem(jaxbItem, currency));
        }

        return items;
    }

    /**
     * Map JAXB line item to domain LineItem
     */
    private LineItem mapLineItem(SupplyChainTradeLineItemType jaxbItem, String currency)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        // Extract product description
        TradeProductType product = jaxbItem.getSpecifiedTradeProduct();
        if (product == null || product.getName() == null || product.getName().isEmpty()) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Line item product name is missing");
        }
        String description = product.getName().get(0).getValue();

        // Extract quantity
        LineTradeDeliveryType delivery = jaxbItem.getSpecifiedLineTradeDelivery();
        if (delivery == null || delivery.getBilledQuantity() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Line item quantity is missing");
        }
        BigDecimal quantityDecimal = delivery.getBilledQuantity().getValue();
        if (quantityDecimal.stripTrailingZeros().scale() > 0) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(
                "Line item quantity must be a whole number, got: " + quantityDecimal);
        }
        int quantity;
        try {
            quantity = quantityDecimal.intValueExact();
        } catch (ArithmeticException e) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(
                "Line item quantity out of integer range: " + quantityDecimal, e);
        }

        // Extract unit price
        LineTradeAgreementType agreement = jaxbItem.getSpecifiedLineTradeAgreement();
        if (agreement == null || agreement.getGrossPriceProductTradePrice() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Line item unit price is missing");
        }
        TradePriceType priceType = agreement.getGrossPriceProductTradePrice();
        if (priceType.getChargeAmount() == null || priceType.getChargeAmount().isEmpty()) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Line item price amount is missing");
        }
        if (priceType.getChargeAmount().size() > 1) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException(
                "Line item has " + priceType.getChargeAmount().size() + " price amounts; "
                + "exactly one ChargeAmount is expected per GrossPriceProductTradePrice");
        }
        BigDecimal unitPriceAmount = priceType.getChargeAmount().get(0).getValue();
        Money unitPrice = Money.of(unitPriceAmount, currency);

        // Extract tax rate
        LineTradeSettlementType settlement = jaxbItem.getSpecifiedLineTradeSettlement();
        BigDecimal taxRate = BigDecimal.ZERO;

        if (settlement != null && settlement.getApplicableTradeTax() != null
            && !settlement.getApplicableTradeTax().isEmpty()) {

            TradeTaxType tax = settlement.getApplicableTradeTax().get(0);
            if (tax.getCalculatedRate() != null) {
                taxRate = tax.getCalculatedRate();
            }
        }

        return new LineItem(description, quantity, unitPrice, taxRate);
    }

    /**
     * Extract currency code
     */
    private String extractCurrency(SupplyChainTradeTransactionType transaction)
            throws TaxInvoiceParserPort.TaxInvoiceParsingException {

        HeaderTradeSettlementType settlement = transaction.getApplicableHeaderTradeSettlement();
        if (settlement == null || settlement.getInvoiceCurrencyCode() == null) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Tax invoice currency is missing");
        }

        String currency = null;
        if (settlement.getInvoiceCurrencyCode().getValue() != null) {
            currency = settlement.getInvoiceCurrencyCode().getValue().value();
        }

        if (currency == null || currency.length() != 3) {
            throw new TaxInvoiceParserPort.TaxInvoiceParsingException("Invalid currency code: " + currency);
        }

        return currency;
    }

    /**
     * Convert XMLGregorianCalendar to LocalDate using Thai timezone (UTC+7).
     * When the XML omits the timezone offset (DatatypeConstants.FIELD_UNDEFINED),
     * the timestamp is interpreted as Asia/Bangkok time — matching the Thai e-Tax standard.
     * Without this, a JVM running at UTC would misparse a late-evening Thai datetime
     * (e.g. 2025-01-01T23:30:00 without offset) as the previous calendar day.
     */
    private LocalDate convertXMLGregorianCalendarToLocalDate(XMLGregorianCalendar calendar) {
        ZoneId zone = (calendar.getTimezone() == DatatypeConstants.FIELD_UNDEFINED)
            ? TH_ZONE
            : calendar.toGregorianCalendar().getTimeZone().toZoneId();
        return calendar.toGregorianCalendar().toZonedDateTime()
            .withZoneSameInstant(zone)
            .toLocalDate();
    }
}
