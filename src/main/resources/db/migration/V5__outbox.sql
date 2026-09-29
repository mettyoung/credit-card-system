CREATE TABLE outbox (
    id             uuid        PRIMARY KEY,
    application_id uuid        NOT NULL,
    type           text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL,
    published_at   timestamptz,
    -- The claim. A row a relay has taken is invisible to the others until it publishes the row or the lease
    -- runs out, so a relay that dies mid-batch releases its work by expiry rather than by cleanup.
    claimed_by     text,
    claimed_until  timestamptz
);

-- The relay only ever wants rows that are unpublished and unclaimed, and there are few of them next to the
-- history. claimed_until is in the index because it is half the predicate.
CREATE INDEX ix_outbox_claimable ON outbox (claimed_until, id) WHERE published_at IS NULL;

-- I8: a consumer processes an event at most once. Inserting the row is how a consumer claims it, so the
-- primary key - not application logic - is what refuses the second attempt.
CREATE TABLE processed_event (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
