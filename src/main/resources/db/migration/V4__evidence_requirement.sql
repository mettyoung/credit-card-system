CREATE TABLE evidence_requirement (
    id                     uuid   PRIMARY KEY,
    application_id         uuid   NOT NULL,
    type                   text   NOT NULL,
    status                 text   NOT NULL,
    source_vendor_check_id uuid,
    source_document_id     uuid,
    version                bigint NOT NULL,
    -- RECEIVED implies a source; anything else must not claim one it did not get from a check.
    CONSTRAINT ck_requirement_received_has_source
        CHECK (status <> 'RECEIVED' OR source_vendor_check_id IS NOT NULL OR source_document_id IS NOT NULL)
);

-- I5: one requirement per type per application. The orchestrator can therefore create it idempotently.
CREATE UNIQUE INDEX ux_requirement_app_type ON evidence_requirement (application_id, type);
