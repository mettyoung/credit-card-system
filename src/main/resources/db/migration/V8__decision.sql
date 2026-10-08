-- FR8: why an application was referred, or why a reviewer declined it. A code, never free text, and never shown
-- to the applicant. The new statuses need nothing here: status is text.
ALTER TABLE application ADD COLUMN decision_reason text;
