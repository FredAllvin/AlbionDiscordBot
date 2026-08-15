-- Makes confirming a split or cashout single-use.
--
-- The confirm buttons are stateless on purpose: everything they need is encoded in the
-- custom id, so a pending confirmation survives a restart with nothing to expire. The
-- cost was that nothing marked a batch as already applied. Each click ran the credit
-- again with a fresh batch id, so a double-click credited the whole role twice and left
-- two independent batches — /undo on the id shown in the message reversed only one of
-- them, and the other stayed on the books looking legitimate. Measured: two identical
-- confirms took a member from 0 to 2,000,000 on a 1,000,000 split.
--
-- The token is minted per preview, not derived from the amount and recipients, so
-- running the same split twice on purpose still works. It is only the *same* preview
-- that cannot be confirmed twice.
CREATE TABLE batch_claim (
    claim_token      VARCHAR(64) PRIMARY KEY,
    discord_guild_id BIGINT      NOT NULL,
    batch_id         VARCHAR(64) NOT NULL,
    claimed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
