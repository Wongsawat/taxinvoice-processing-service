-- V3__remove_duplicate_invoice_number_index.sql
-- Remove duplicate non-unique index on invoice_number
-- The unique index idx_tax_invoice_number_unique already covers this column

DROP INDEX IF EXISTS idx_tax_invoice_number;
