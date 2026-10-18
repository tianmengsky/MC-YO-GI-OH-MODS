package dev.codex.yugiohmdpro.duel;

/**
 * The beat state machine that turns the reference host's message stream into a paced presentation.
 *
 * <p>One instance drives one duel. The owner consumes exactly one host event per call while the
 * pacer is {@link #ready()}, publishes the board when {@link #beat} says so, and then leaves the
 * pacer holding for the message's animation time. Messages that are visible but change nothing on
 * the board (the core has already applied a whole {@code process()} batch) are folded into the
 * previous beat instead of adding dead time, but they still reach the duel log.
 *
 * <p>Pure: no Minecraft, no native, no clock. The owner supplies ticks.
 */
public final class DuelPacer {
    private boolean instant;
    private int hold;
    private long last = Long.MIN_VALUE;
    private int beats, folded, holds;

    public DuelPacer(boolean instant) { this.instant = instant; }

    public boolean instant() { return instant; }

    public void instant(boolean value) {
        instant = value;
        if (value) hold = 0;
    }

    /** True while the next host event may be consumed. */
    public boolean ready() { return hold <= 0; }

    /** Remaining hold in server ticks. */
    public int hold() { return hold; }

    /** Consumes one server tick of the hold. */
    public void tick() { if (hold > 0) hold--; }

    /**
     * Feeds one visible core message.
     *
     * @param kind        core message id
     * @param fingerprint the display state that message produced (board, LP, turn, phase)
     * @return true when the owner must publish the current state now and then wait {@link #hold()}
     */
    public boolean beat(int kind, long fingerprint) {
        if (instant) { beats++; hold = 0; return true; }
        boolean changed = fingerprint != last;
        if (!changed && !DuelPacing.narrative(kind)) { folded++; hold = 0; return false; }
        last = fingerprint;
        beats++;
        hold = DuelPacing.holdTicks(kind);
        holds += hold;
        return true;
    }

    /** Nothing queued right now: retry on the next tick without a hold. */
    public void idle() { hold = 0; }

    /** A request reached the player: the queue stops until it is answered, whatever the hold was. */
    public void prompt() { hold = 0; }

    public void reset() { hold = 0; last = Long.MIN_VALUE; beats = 0; folded = 0; holds = 0; }

    /** Published beats so far: exactly the number of duel-screen updates this duel produced. */
    public int beats() { return beats; }

    /** Visible messages folded into an earlier beat because the board was unchanged. */
    public int folded() { return folded; }

    /** Total server ticks spent holding for animation between beats. */
    public int holds() { return holds; }
}
