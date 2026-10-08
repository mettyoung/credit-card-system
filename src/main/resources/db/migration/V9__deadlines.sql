-- FR9: when the application entered its current status. Both deadlines read it - "in NEEDS_INFO since" and
-- "referred since". Existing rows get the migration time, so applications already waiting start their deadline
-- when this ships rather than expiring at once.
ALTER TABLE application ADD COLUMN status_changed_at timestamptz;
UPDATE application SET status_changed_at = now() WHERE status <> 'DRAFT';

-- The sweep's query: by status and age, never by scanning every application.
CREATE INDEX ix_application_status_age ON application (status, status_changed_at);
