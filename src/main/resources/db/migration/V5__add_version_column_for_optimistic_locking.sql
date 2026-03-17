-- V5__add_version_column_for_optimistic_locking.sql
-- Add @Version column to processed_tax_invoices for JPA optimistic locking.
-- Guards against concurrent updates from non-saga paths (e.g. future admin endpoints)
-- silently overwriting each other with last-write semantics.

ALTER TABLE processed_tax_invoices
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
