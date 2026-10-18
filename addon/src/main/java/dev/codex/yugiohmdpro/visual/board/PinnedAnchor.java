// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 */
package dev.codex.yugiohmdpro.visual.board;

import dev.codex.yugiohmdpro.visual.board.BoardLayout;

public final class PinnedAnchor {
    private boolean pinned;
    private double x;
    private double y;
    private double z;
    private double yaw;

    public boolean pinned() {
        return this.pinned;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public double yaw() {
        return this.yaw;
    }

    public double[] forward() {
        return BoardLayout.forwardFromYaw(this.yaw);
    }

    public void pin(double x, double y, double z, double yawDegrees) {
        if (this.pinned) {
            return;
        }
        this.pinned = true;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yawDegrees;
    }

    public void clear() {
        this.pinned = false;
    }
}

