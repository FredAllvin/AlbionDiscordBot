-- A CTA is now measured by how many of OUR players showed up, not by how big the
-- fight was overall.
--
-- Total battle size counted the enemy too, so a 3-man gank squad caught in someone
-- else's 40-player brawl registered as a CTA, while a 15-man guild op against a small
-- group did not. Neither matches what the threshold is meant to express.

ALTER TABLE battle_participation ADD COLUMN guild_player_count INT NOT NULL DEFAULT 0;

-- Backfill from what is already stored: participation rows only ever exist for tracked
-- guilds, so counting them per battle is exactly "how many of ours were there".
UPDATE battle_participation bp
SET guild_player_count = sub.count
FROM (
    SELECT albion_battle_id, count(*) AS count
    FROM battle_participation
    GROUP BY albion_battle_id
) sub
WHERE bp.albion_battle_id = sub.albion_battle_id;

CREATE INDEX ix_participation_guild_count ON battle_participation (guild_player_count);

-- Ten of our own is a real turnout; thirty of our own is a full ZvZ and would almost
-- never fire. Only servers still on the previous default are moved, so a deliberately
-- chosen threshold is left alone.
ALTER TABLE discord_guild_config ALTER COLUMN cta_min_players SET DEFAULT 10;
UPDATE discord_guild_config SET cta_min_players = 10 WHERE cta_min_players = 30;
