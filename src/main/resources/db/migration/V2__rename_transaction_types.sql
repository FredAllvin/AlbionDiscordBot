-- Vocabulary correction.
--
-- "Payout" now means a CASHOUT: the member has been handed silver in game and their
-- balance is cleared. Crediting a share of loot into the ledger is a SPLIT — the
-- opposite direction. The old PAYOUT name meant the latter, which read backwards.
--
-- V1 is left untouched so Flyway's checksum for it stays valid on databases that have
-- already applied it.

UPDATE balance_transaction SET type = 'SPLIT'    WHERE type = 'PAYOUT';
UPDATE balance_transaction SET type = 'REVERSAL' WHERE type = 'PAYOUT_UNDO';
