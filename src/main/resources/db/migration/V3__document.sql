CREATE TABLE document (
    id             uuid         PRIMARY KEY,
    application_id uuid         NOT NULL,
    user_id        text         NOT NULL,
    kind           text         NOT NULL,
    object_key     text         NOT NULL,
    sha256         varchar(64)  NOT NULL,
    size_bytes     bigint       NOT NULL,
    content_type   text         NOT NULL,
    status         text         NOT NULL,
    created_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL,
    CONSTRAINT ck_document_size CHECK (size_bytes > 0 AND size_bytes <= 10485760)
);

CREATE INDEX ix_document_application ON document (application_id);

-- The upload-cleanup sweep: stale rows are found by status and age, never by scanning every document.
CREATE INDEX ix_document_pending ON document (status, created_at) WHERE status = 'PENDING_UPLOAD';

-- One object per document, so a retried request can never point two rows at the same bytes.
CREATE UNIQUE INDEX ux_document_object_key ON document (object_key);
