CREATE TABLE vendor_inbox (
    id           uuid        PRIMARY KEY,
    vendor       text        NOT NULL,
    event_id     text        NOT NULL,
    vendor_ref   text        NOT NULL,
    received_at  timestamptz NOT NULL,
    processed_at timestamptz
);

-- I7: a webhook event has one effect however many times it is delivered. Onfido sends no event id, so the
-- key is the resource plus the action, which is stable across redeliveries of the same completion.
CREATE UNIQUE INDEX ux_vendor_inbox_event ON vendor_inbox (vendor, event_id);

CREATE INDEX ix_vendor_inbox_unprocessed ON vendor_inbox (id) WHERE processed_at IS NULL;
