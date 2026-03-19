package com.wpanther.taxinvoice.processing.domain.port.out;

import com.wpanther.taxinvoice.processing.domain.model.ProcessedTaxInvoice;

/**
 * Outbound port for parsing XML tax invoices into domain models.
 */
public interface TaxInvoiceParserPort {

    /**
     * Parse XML content into ProcessedTaxInvoice domain model
     *
     * @param xmlContent The XML tax invoice content
     * @param sourceInvoiceId The source invoice ID from intake service
     * @return Parsed tax invoice domain model
     * @throws TaxInvoiceParsingException if parsing fails
     */
    ProcessedTaxInvoice parse(String xmlContent, String sourceInvoiceId) throws TaxInvoiceParsingException;

    /**
     * Exception thrown when tax invoice parsing fails.
     *
     * <p>Use the static factory methods for the common parse-phase failures so that
     * message strings are defined in one place and callers stay readable.
     */
    class TaxInvoiceParsingException extends Exception {
        public TaxInvoiceParsingException(String message) {
            super(message);
        }

        public TaxInvoiceParsingException(String message, Throwable cause) {
            super(message, cause);
        }

        /** Null or blank XML input. */
        public static TaxInvoiceParsingException forEmpty() {
            return new TaxInvoiceParsingException("XML content is null or empty");
        }

        /** Payload exceeds the configured size limit. */
        public static TaxInvoiceParsingException forOversized(int byteSize, int limitBytes) {
            return new TaxInvoiceParsingException(
                "XML payload too large: " + byteSize + " bytes (limit " + limitBytes + " bytes / 500 KB)");
        }

        /** JAXB unmarshal did not finish within the allowed wall-clock window. */
        public static TaxInvoiceParsingException forTimeout(long timeoutMs) {
            return new TaxInvoiceParsingException(
                "XML parsing timed out after " + timeoutMs + " ms — possible malformed input");
        }

        /** The executor thread was interrupted before unmarshal completed. */
        public static TaxInvoiceParsingException forInterrupted() {
            return new TaxInvoiceParsingException("XML parsing was interrupted");
        }

        /** JAXB or SAX threw an exception during unmarshal. */
        public static TaxInvoiceParsingException forUnmarshal(Throwable cause) {
            return new TaxInvoiceParsingException("XML parsing failed: " + cause.getMessage(), cause);
        }

        /** Unmarshalled object is not the expected root type. */
        public static TaxInvoiceParsingException forUnexpectedRootElement(String className) {
            return new TaxInvoiceParsingException("Unexpected root element: " + className);
        }
    }
}
