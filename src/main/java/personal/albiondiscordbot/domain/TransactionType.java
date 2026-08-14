package personal.albiondiscordbot.domain;

/**
 * Reason a balance changed. Persisted as a string, never an ordinal.
 *
 * <p>Note the direction of the two batch operations, which are opposites:
 * {@link #SPLIT} puts silver <em>into</em> the ledger (the guild now owes it), while
 * {@link #CASHOUT} takes it back out because the member has been paid in game.
 */
public enum TransactionType {
    ADD,
    REMOVE,
    RESET,
    /** Silver leaving the sender in a {@code /balance give}. */
    GIVE_OUT,
    /** Silver arriving at the recipient in a {@code /balance give}. */
    GIVE_IN,
    /** A share of loot credited to someone's balance — the guild now owes it. */
    SPLIT,
    /** Balance cleared because the member was actually sent the silver in game. */
    CASHOUT,
    /**
     * Reversal of a {@link #SPLIT} or {@link #CASHOUT} batch. Carries the same
     * {@code reference} as the batch it undoes, so the pair reads back together.
     */
    REVERSAL
}
