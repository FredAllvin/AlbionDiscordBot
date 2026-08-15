-- Makes "this batch has already been reversed" a fact the database enforces.
--
-- undoBatch read the batch, checked for a REVERSAL row, then wrote. Under READ COMMITTED
-- no transaction sees another's uncommitted reversal, so every concurrent /undo passed
-- the check and applied a full reversal. Measured with eight simultaneous calls: eight
-- reversals applied, none refused, a member driven from 1,000,000 to -7,000,000. The
-- ledger recorded all eight as valid REVERSAL rows, so the audit trail agreed with the
-- wrong number.
--
-- undoBatch now also locks the batch's rows before checking, which is what produces the
-- readable "already reversed" message. This index is the guarantee underneath it: even
-- if that lock is ever refactored away, the second reversal cannot commit.

-- Fail with something actionable rather than a bare index violation if a batch was
-- already double-reversed before this migration ran. Nothing is deleted automatically:
-- these are ledger rows explaining real balances, so a human decides what happened.
DO $$
DECLARE affected int;
BEGIN
    SELECT count(*) INTO affected FROM (
        SELECT reference, discord_user_id
        FROM balance_transaction
        WHERE type = 'REVERSAL' AND reference IS NOT NULL
        GROUP BY reference, discord_user_id
        HAVING count(*) > 1
    ) duplicates;

    IF affected > 0 THEN
        RAISE EXCEPTION
            'Cannot enforce one-reversal-per-batch: % member/batch pair(s) were already reversed more than once. '
            'Run: SELECT reference, discord_user_id, count(*) FROM balance_transaction '
            'WHERE type = ''REVERSAL'' GROUP BY 1, 2 HAVING count(*) > 1; '
            'then correct those balances with /balance add or /balance remove and delete the surplus rows.',
            affected;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_reversal_once
    ON balance_transaction (reference, discord_user_id)
    WHERE type = 'REVERSAL' AND reference IS NOT NULL;
