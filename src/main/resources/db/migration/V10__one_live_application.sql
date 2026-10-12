-- I4 / FR1.5: one live application per user per card product. Replaces V1's one-draft rule, which only covered
-- DRAFT and so let a second application start while the first was being checked, or after it was approved.
--
-- NOT IN rather than IN, so a status added later is covered until someone decides it frees the slot: a forgotten
-- status blocks a reapply instead of allowing a duplicate. Every status a live application can move through is
-- inside the index, so no transition between them can violate it - the only write that can is the INSERT in
-- createDraft, which is where the refusal belongs. DECLINED and EXPIRED are terminal and never re-enter.
--
-- Fails if an account already holds two live applications for one product. Resolve those first; this migration
-- does not guess which one to keep.
CREATE UNIQUE INDEX ux_application_one_live
    ON application (user_id, card_product_code)
    WHERE status NOT IN ('DECLINED', 'EXPIRED');

DROP INDEX ux_application_one_draft;
