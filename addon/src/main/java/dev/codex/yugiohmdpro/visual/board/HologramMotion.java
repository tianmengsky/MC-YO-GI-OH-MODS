// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 */
package dev.codex.yugiohmdpro.visual.board;

public final class HologramMotion {
    private static final double TWO_PI = Math.PI * 2;
    public static final double DEFAULT_HEIGHT = 1.55;
    public static final double HOVER_RADIANS_PER_SECOND = 1.2;
    public static final double HOVER_PHASE_STEP = 0.4;
    public static final int HOVER_PHASE_STEPS = 16;

    private HologramMotion() {
    }

    public static double hoverPhase(double seconds, int uid) {
        return seconds * 1.2 + (double)(uid % 16) * 0.4;
    }

    public static Pose rest() {
        return Pose.rest();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static Pose pose(Action action, double seconds, Tuning tuning) {
        if (action == null || tuning == null) {
            return Pose.rest();
        }
        return switch (action) {
            default -> throw new IncompatibleClassChangeError();
            case IDLE -> HologramMotion.idle(seconds, tuning);
            case ATTACK -> HologramMotion.attack(seconds / Math.max(0.05, tuning.duration()), tuning);
            case HIT -> HologramMotion.hit(seconds / Math.max(0.05, tuning.duration()), tuning);
        };
    }

    public static boolean over(Action action, double seconds, Tuning tuning) {
        return action != null && action != Action.IDLE && seconds > Math.max(0.05, tuning.duration());
    }

    public static Pose idle(double seconds, Tuning t) {
        return new Pose(t.bob() * Math.sin(Math.PI * 2 * t.wingSpeed() * 0.5 * seconds), 0.0, 0.0, 0.0, t.wingFlap() * Math.sin(Math.PI * 2 * t.wingSpeed() * seconds), t.headNod() * Math.sin(Math.PI * 2 * t.headSpeed() * seconds + 0.7), t.tailSway() * Math.sin(Math.PI * 2 * t.tailSpeed() * seconds));
    }

    public static Pose attack(double progress, Tuning t) {
        double swing = Math.sin(Math.PI * Math.min(1.0, Math.max(0.0, progress) / 0.8));
        double burst = Math.sin(Math.PI * 2 * t.wingSpeed() * progress) * (1.0 - Math.max(0.0, Math.min(1.0, progress)));
        return new Pose(0.0, t.bodyPitch() * swing, t.lunge() * swing, 0.0, t.wingFlap() * burst, t.headNod() * swing, 0.0);
    }

    public static Pose hit(double progress, Tuning t) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        double swing = Math.sin(Math.PI * Math.min(1.0, clamped / 0.8));
        double jitter = Math.sin(Math.PI * 6 * clamped) * (1.0 - clamped);
        return new Pose(0.0, t.bodyPitch() * swing, -t.recoil() * swing, t.shake() * jitter, -t.wingFlap() * swing, 0.0, 0.0);
    }

    public record Pose(double bob, double pitch, double lunge, double shake, double wingFlap, double headNod, double tailSway) {
        public static Pose rest() {
            return new Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public static enum Action {
        IDLE,
        ATTACK,
        HIT;

    }

    public record Tuning(double bob, double wingFlap, double wingSpeed, double tailSway, double tailSpeed, double headNod, double headSpeed, double duration, double lunge, double bodyPitch, double recoil, double shake) {
        public static Tuning idle() {
            return new Tuning(5.0, 9.0, 1.1, 6.0, 0.8, 4.0, 1.1, 1.0, 0.0, 0.0, 0.0, 0.0);
        }

        public static Tuning attack() {
            return new Tuning(0.0, 34.0, 4.0, 0.0, 0.0, 10.0, 0.0, 1.0, 22.0, 9.0, 0.0, 0.0);
        }

        public static Tuning hit() {
            return new Tuning(0.0, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.8, 0.0, -14.0, 13.0, 1.4);
        }
    }
}

