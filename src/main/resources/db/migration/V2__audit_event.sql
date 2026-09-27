CREATE TABLE audit_event (
    id             uuid        PRIMARY KEY,
    application_id uuid        NOT NULL,
    seq            bigint      NOT NULL,
    type           text        NOT NULL,
    actor          text        NOT NULL,
    actor_id       text,
    payload        jsonb       NOT NULL,
    at             timestamptz NOT NULL
);

-- I10: seq is contiguous per application, so a missing row is visible rather than silent.
CREATE UNIQUE INDEX ux_audit_app_seq ON audit_event (application_id, seq);

-- I9: append-only.
--
-- A REVOKE would not do it. The application owns this table, and an owner keeps its rights whatever is
-- revoked, so "the app's role has INSERT and SELECT only" would be a claim the schema does not enforce. A
-- trigger holds regardless of who is connected.
--
-- The production answer is a separate owner role that grants only INSERT and SELECT to the application role.
-- That costs the app ownership of its own schema and gives Flyway its own credentials, so it is deferred - but
-- this is a guard, not the real thing.
CREATE OR REPLACE FUNCTION audit_event_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_is_append_only();
