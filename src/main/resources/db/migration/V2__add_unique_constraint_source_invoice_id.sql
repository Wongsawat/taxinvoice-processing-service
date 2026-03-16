-- V2__add_unique_constraint_source_invoice_id.sql
-- Add unique constraint on source_invoice_id to prevent duplicate processing
-- This is critical for idempotency under concurrent load

ALTER TABLE processed_tax_invoices
ADD CONSTRAINT uq_processed_tax_invoices_source_invoice_id UNIQUE (source_invoice_id);
