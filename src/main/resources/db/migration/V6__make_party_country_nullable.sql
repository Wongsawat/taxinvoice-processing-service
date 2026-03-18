-- Make the country column on tax_invoice_parties nullable so that parties without
-- a PostalTradeAddress (optional per Thai e-Tax XSD) are stored as NULL rather
-- than the misleading sentinel value "UNKNOWN".  A NULL country correctly signals
-- "no address" through the JPA entity and round-trips back to a null domain
-- Address, preserving domain invariants across persist/load cycles.
ALTER TABLE tax_invoice_parties
    ALTER COLUMN country DROP NOT NULL;
