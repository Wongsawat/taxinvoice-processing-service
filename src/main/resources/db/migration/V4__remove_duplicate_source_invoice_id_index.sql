-- V4__remove_duplicate_source_invoice_id_index.sql
-- Remove the non-unique index on source_invoice_id created in V1.
-- V2 added a UNIQUE constraint on the same column, which PostgreSQL backs with
-- its own implicit B-tree index. Having both doubles write overhead and wastes
-- storage with no query-performance benefit.

DROP INDEX IF EXISTS idx_tax_source_invoice_id;
