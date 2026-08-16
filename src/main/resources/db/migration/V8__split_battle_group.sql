-- Lets one /split-cta cover several battles at once.
--
-- One CTA regularly produces more than one battle on the killboard: the fight breaks
-- off, everyone reforms, and Albion opens a new battle id. Splitting each of them
-- separately pays whoever was in two of them twice, so the officer names all of them at
-- once and the split credits the union — every person exactly once.
--
-- The confirm buttons keep all their other state in the Discord custom id, which caps at
-- 100 characters. Two snowflakes, an amount and a claim token already spend 61 of those,
-- leaving room for four 9-digit battle ids today and three once ids reach ten digits. A
-- cap that quietly tightens over time is the worst version of this: it would force an
-- officer with five killboards to run the split twice, which is exactly the double-pay
-- the feature exists to prevent. So the ids live here and the button carries a key.
--
-- Rows are kept rather than expired. A preview's buttons have to keep working across a
-- restart, and the Copy list button outlives the confirmation that created it — the same
-- reason nothing else about a pending batch is held in memory. Each row is a few dozen
-- bytes and one is written per /split-cta.
CREATE TABLE split_battle_group (
    group_key        VARCHAR(64) PRIMARY KEY,
    -- Scoped to the server that asked, so a key cannot be replayed from another one.
    discord_guild_id BIGINT      NOT NULL,
    -- Albion battle ids, comma-separated, in the order the officer typed them.
    battle_ids       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
