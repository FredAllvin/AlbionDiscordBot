-- A CTA now has to satisfy two conditions at once: the fight has to be big, AND enough
-- of our own have to be in it.
--
-- Either test alone lets the wrong thing through. Total size alone counts a small party
-- swept into someone else's zerg. Our turnout alone counts ten of us ganking three
-- people. Requiring both is what actually describes a call to arms.
--
-- The old column is renamed rather than reused, because "cta_min_players" alongside a
-- second threshold would leave nobody able to remember which one it meant.

ALTER TABLE discord_guild_config RENAME COLUMN cta_min_players TO cta_min_guild_players;

ALTER TABLE discord_guild_config
    ADD COLUMN cta_min_total_players INT NOT NULL DEFAULT 30;
