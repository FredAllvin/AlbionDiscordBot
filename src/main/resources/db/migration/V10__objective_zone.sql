-- Where the objective is (/objective add … zone:Fort Sterling).
--
-- Optional, because intel usually arrives as one phrase — "Fort Sterling chest 20:00" —
-- and making somebody take it apart before they can post it slows down the thing the
-- board exists to make fast. Once a zone is available the names get shorter and more
-- generic, though, and that changes what counts as the same objective: "Chest" in Fort
-- Sterling and "Chest" in Martlock are two of them, and they can pop in the same minute.
-- Under ux_objective_name_time the second one was refused as a duplicate of the first.
ALTER TABLE objective ADD COLUMN zone VARCHAR(60);

-- So the zone joins the identity of a line on the board.
--
-- coalesce, and not the column on its own: Postgres counts NULLs in a unique index as
-- distinct from each other, so indexing zone directly would let "Chest 20:00" with no
-- zone go up twice over — the exact duplicate ux_objective_name_time existed to stop,
-- and the common case rather than an edge one. Folding NULL to '' gives every zoneless
-- row one shared key again.
--
-- Safe to apply to a board already in use: adding a column to a unique key can only
-- admit rows the narrower key already allowed, so nothing existing can fail it.
DROP INDEX ux_objective_name_time;
CREATE UNIQUE INDEX ux_objective_slot
    ON objective (discord_guild_id, lower(name), lower(coalesce(zone, '')), pops_at);
