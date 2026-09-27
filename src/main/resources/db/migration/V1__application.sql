CREATE TABLE application (
    id                 uuid         PRIMARY KEY,
    user_id            text         NOT NULL,
    card_product_code  text         NOT NULL,
    status             text         NOT NULL,
    first_name         varchar(70),
    last_name          varchar(70),
    date_of_birth      date,
    country            varchar(2),
    version            bigint       NOT NULL,
    -- The absolute half of DateOfBirth's rule. "In the past" cannot be a CHECK, because current_date is not
    -- immutable; the value object holds both halves and the column holds the one that can never become false.
    CONSTRAINT ck_application_date_of_birth_floor
        CHECK (date_of_birth IS NULL OR date_of_birth >= DATE '1900-01-01')
);

-- I4 / FR1.5: one draft per user per product. Unenforceable in memory under concurrency, which is why the
-- service inserts and translates the violation rather than checking first.
CREATE UNIQUE INDEX ux_application_one_draft
    ON application (user_id, card_product_code)
    WHERE status = 'DRAFT';

-- FR1.4: list own applications.
CREATE INDEX ix_application_user ON application (user_id);
