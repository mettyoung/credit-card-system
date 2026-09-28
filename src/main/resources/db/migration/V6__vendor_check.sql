CREATE TABLE vendor_check (
    id              uuid        PRIMARY KEY,
    application_id  uuid        NOT NULL,
    type            text        NOT NULL,
    provider        text        NOT NULL,
    document_id     uuid,
    idempotency_key text        NOT NULL,
    status          text        NOT NULL,
    vendor_ref      text,
    outcome         text,
    failure_code    text,
    raw_response    text,
    attempts        integer     NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    lease_until     timestamptz,
    deadline_at     timestamptz,
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL
);

-- I6: one check per idempotency key. This is the database half of "a retry must not pay twice" - the other
-- half is the key we send the vendor.
CREATE UNIQUE INDEX ux_vendor_check_key ON vendor_check (idempotency_key);

-- The claim query: work that is due, plus work whose worker died and left an expired lease.
CREATE INDEX ix_vendor_check_claim ON vendor_check (status, next_attempt_at);

-- Completing a check from a webhook looks it up by the vendor's own handle.
CREATE UNIQUE INDEX ux_vendor_check_ref ON vendor_check (vendor_ref) WHERE vendor_ref IS NOT NULL;
