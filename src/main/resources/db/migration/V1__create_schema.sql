-- processed_tax_invoices
--   version:           optimistic-locking column (JPA @Version)
--   source_invoice_id: unique constraint named explicitly so the DuplicateKeyException
--                      handler in TaxInvoiceProcessingService can identify it by name
--                      and distinguish race-condition duplicates from data errors.
CREATE TABLE processed_tax_invoices (
    id               UUID          PRIMARY KEY,
    source_invoice_id VARCHAR(100) NOT NULL,
    invoice_number   VARCHAR(50)   NOT NULL,
    issue_date       DATE          NOT NULL,
    due_date         DATE          NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    subtotal         DECIMAL(15,2) NOT NULL,
    total_tax        DECIMAL(15,2) NOT NULL,
    total            DECIMAL(15,2) NOT NULL,
    original_xml     TEXT          NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    error_message    TEXT,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_processed_tax_invoices_source_invoice_id UNIQUE (source_invoice_id)
);

CREATE INDEX        idx_tax_status       ON processed_tax_invoices(status);
CREATE INDEX        idx_tax_issue_date   ON processed_tax_invoices(issue_date);
CREATE UNIQUE INDEX idx_tax_invoice_number_unique ON processed_tax_invoices(invoice_number);

-- tax_invoice_parties
--   country: nullable — NULL means the party has no postal address (PostalTradeAddress
--            is optional per the Thai e-Tax XSD).  Storing NULL avoids the need for a
--            sentinel value such as 'UNKNOWN' that would corrupt domain Address objects
--            on round-trip through the mapper.
CREATE TABLE tax_invoice_parties (
    id             UUID         PRIMARY KEY,
    invoice_id     UUID         NOT NULL,
    party_type     VARCHAR(10)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    tax_id         VARCHAR(50),
    tax_id_scheme  VARCHAR(20),
    street_address VARCHAR(500),
    city           VARCHAR(100),
    postal_code    VARCHAR(20),
    country        VARCHAR(100),
    email          VARCHAR(200),
    CONSTRAINT fk_tax_invoice_parties_invoice
        FOREIGN KEY (invoice_id) REFERENCES processed_tax_invoices(id) ON DELETE CASCADE
);

CREATE INDEX idx_tax_party_invoice ON tax_invoice_parties(invoice_id);
CREATE INDEX idx_tax_party_type    ON tax_invoice_parties(party_type);

-- tax_invoice_line_items
CREATE TABLE tax_invoice_line_items (
    id          UUID          PRIMARY KEY,
    invoice_id  UUID          NOT NULL,
    line_number INTEGER       NOT NULL,
    description VARCHAR(500)  NOT NULL,
    quantity    INTEGER       NOT NULL,
    unit_price  DECIMAL(15,2) NOT NULL,
    tax_rate    DECIMAL(5,2)  NOT NULL,
    line_total  DECIMAL(15,2) NOT NULL,
    tax_amount  DECIMAL(15,2) NOT NULL,
    CONSTRAINT fk_tax_invoice_line_items_invoice
        FOREIGN KEY (invoice_id) REFERENCES processed_tax_invoices(id) ON DELETE CASCADE
);

CREATE INDEX        idx_tax_line_item_invoice  ON tax_invoice_line_items(invoice_id);
CREATE UNIQUE INDEX idx_tax_invoice_line_number ON tax_invoice_line_items(invoice_id, line_number);

-- outbox_events (Transactional Outbox / Debezium CDC)
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    topic          VARCHAR(255),
    partition_key  VARCHAR(255),
    headers        TEXT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count    INTEGER      DEFAULT 0,
    error_message  VARCHAR(1000),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMP
);

CREATE INDEX idx_outbox_status    ON outbox_events(status);
CREATE INDEX idx_outbox_created   ON outbox_events(created_at);
CREATE INDEX idx_outbox_debezium  ON outbox_events(created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);

COMMENT ON COLUMN outbox_events.payload IS 'Event payload as JSON text (portable across databases)';
COMMENT ON COLUMN outbox_events.headers IS 'Kafka headers as JSON text (portable across databases)';
