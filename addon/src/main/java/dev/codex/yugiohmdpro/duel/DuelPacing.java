package dev.codex.yugiohmdpro.duel;

import java.util.Locale;

/**
 * Pacing policy for the duel presentation, modelled on MDPro3's message queue.
 *
 * <p>MDPro3 handles one core message per frame and blocks the queue until that message's animation
 * finished ({@code OcgCore.messagePass}, {@code OcgCore.cs:1828/2768/2817}); "important" messages
 * additionally wait for a minimum wall-clock interval ({@code Sleep(framsIn100)} +
 * {@code MessageBeginTime}, {@code OcgCore.cs:1653-1657/1834-1836}), and the interval is derived
 * from the animation duration itself ({@code Sleep((int)(card.Move(to) * 100))},
 * {@code OcgCore.cs:3029}). This class carries the same idea into a server-tick budget: every
 * visible core message gets a hold measured in server ticks, scaled by a per-duel speed mode.
 *
 * <p>Pure policy: no Minecraft, no native, no I/O. Everything here is unit-testable and shared by
 * {@link DuelBridge} and the standalone pacing smoke.
 */
public final class DuelPacing {
    private DuelPacing() {}

    public static final int INSTANT = 0, FAST = 1, NORMAL = 2, SLOW = 3;

    /**
     * Core messages that carry a board snapshot, taken from the reference adapter's own list
     * ({@code addon/duel-host-adapter/Adapter.cs:71}). A message outside this set cannot change what
     * the duel screen shows, so pacing it would only add dead time.
     */
    private static final int[] VISIBLE = {5, 40, 41, 50, 53, 54, 55, 60, 61, 62, 63, 64, 65,
            90, 91, 92, 94, 100, 101, 102, 110, 111};

    /**
     * Messages that deserve their own beat even when the board did not change: turn and phase
     * banners, attack and battle declarations, and the win message. This is the local counterpart of
     * MDPro3's {@code IfMessageImportant} ({@code OcgCore.cs:2267-2314}).
     */
    private static final int[] NARRATIVE = {5, 40, 41, 110, 111};

    private static volatile int mode = fromProperty();

    private static int fromProperty() {
        String value = System.getProperty("yugioh.mdpro.pacing", "normal");
        return parse(value);
    }

    public static int parse(String value) {
        return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
            case "instant", "off", "0" -> INSTANT;
            case "fast", "1" -> FAST;
            case "slow", "3" -> SLOW;
            default -> NORMAL;
        };
    }

    public static void mode(int value) { mode = value < INSTANT || value > SLOW ? NORMAL : value; }
    public static int mode() { return mode; }
    public static boolean instant() { return mode == INSTANT; }

    public static String name(int value) {
        return switch (value) {
            case INSTANT -> "极速（不节拍）";
            case FAST -> "快速";
            case SLOW -> "慢速";
            default -> "正常";
        };
    }

    /** True when the adapter attaches a board snapshot to this core message. */
    public static boolean visible(int kind) {
        for (int candidate : VISIBLE) if (candidate == kind) return true;
        return false;
    }

    /** True when this message must be shown even without a board change. */
    public static boolean narrative(int kind) {
        for (int candidate : NARRATIVE) if (candidate == kind) return true;
        return false;
    }

    /** Ticks the queue is held after this message, at the current speed mode. */
    public static int holdTicks(int kind) {
        if (instant()) return 0;
        return scale(base(kind));
    }

    /** Server ticks the engine waits before its next automatic decision (AI or fixture). */
    public static int aiDelay() {
        return switch (mode) {
            case INSTANT -> 1;
            case FAST -> 6;
            case SLOW -> 20;
            default -> 12;
        };
    }

    /**
     * Base hold per core message, in ticks at 20 ticks/s. The relative sizes follow the animation
     * durations the client already knows ({@code DuelAnimationEvents.durationSeconds}): a summon is
     * longer than a position change, an attack longer than a draw.
     */
    static int base(int kind) {
        return switch (kind) {
            case 5 -> 20;    // Win
            case 40 -> 16;   // NewTurn: room for the turn banner
            case 41 -> 10;   // NewPhase: room for the phase banner
            case 50 -> 8;    // Move
            case 53 -> 6;    // PosChange
            case 54 -> 8;    // Set
            case 55 -> 8;    // Swap
            case 60 -> 14;   // Summoning
            case 61 -> 4;    // Summoned
            case 62 -> 14;   // SpSummoning
            case 63 -> 4;    // SpSummoned
            case 64 -> 10;   // FlipSummoning
            case 65 -> 4;    // FlipSummoned
            case 90 -> 6;    // Draw
            case 91 -> 10;   // Damage
            case 92 -> 6;    // Recover
            case 94 -> 4;    // LpUpdate
            case 100 -> 4;   // PayLpCost
            case 101, 102 -> 4; // AddCounter / RemoveCounter
            case 110 -> 10;  // Attack
            case 111 -> 12;  // Battle
            default -> 6;
        };
    }

    private static int scale(int ticks) {
        double factor = switch (mode) {
            case FAST -> 0.4;
            case SLOW -> 1.75;
            default -> 1.0;
        };
        return Math.max(1, Math.min(60, (int) Math.round(ticks * factor)));
    }
}
