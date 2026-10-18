// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 */
package dev.codex.yugiohmdpro.visual.board;

public final class BoardLayout {
    public static final int ZONES = 5;
    public static final float SPACING = 3.6f;
    public static final float HALF_WIDTH = 0.686f;
    public static final float HALF_LENGTH = 1.0f;
    public static final float LEAN = 0.0f;
    public static final float THICKNESS = 0.04f;
    public static final double RANGE = 32.0;
    public static final float ART_U0 = 0.1f;
    public static final float ART_U1 = 0.888f;
    public static final float ART_V0 = 0.216f;
    public static final float ART_V1 = 0.644f;
    public static final float CARD_ASPECT = 0.6848249f;
    public static final float HOLOGRAM_SCALE = 2.4f;
    public static final float HOLOGRAM_HOVER = 0.35f;
    public static final float HOLOGRAM_LEAN = 4.0f;
    public static final float MODEL_BASE_HEIGHT = 1.0f;
    public static final float MODEL_MAX_WIDTH = 3.3999999f;
    public static final float MODEL_ALLOWED_WIDTH = 6.7999997f;
    public static final double MIN_SEPARATION = 1.0E-6;

    public static float hologramWidth() {
        return 3.2928002f;
    }

    public static float modelScale(float wantedHeight, float meshHeight, float meshWidth) {
        return BoardLayout.modelScale(wantedHeight, meshHeight, meshWidth, 3.3999999f);
    }

    public static float modelScale(float wantedHeight, float meshHeight, float meshWidth, float requestedWidth) {
        float limit = requestedWidth <= 0.0f ? 3.3999999f : Math.min(requestedWidth, 6.7999997f);
        float byHeight = wantedHeight / Math.max(1.0f, meshHeight);
        float byWidth = meshWidth <= 0.001f ? byHeight : limit / meshWidth;
        return Math.min(byHeight, byWidth);
    }

    public static float[][] hologramCorners(double centerX, double centerY, double centerZ, double towardX, double towardZ, double artAspect, double lift) {
        double halfWidth = 1.6464000940322876;
        double halfHeight = halfWidth / artAspect;
        double rightX = -towardZ;
        double rightZ = towardX;
        double lean = Math.toRadians(4.0);
        double base = centerY + (double)0.35f + lift;
        double riseX = -towardX * Math.sin(lean) * halfHeight * 2.0;
        double riseZ = -towardZ * Math.sin(lean) * halfHeight * 2.0;
        double riseY = Math.cos(lean) * halfHeight * 2.0;
        return new float[][]{{(float)(centerX - rightX * halfWidth), (float)base, (float)(centerZ - rightZ * halfWidth)}, {(float)(centerX + rightX * halfWidth), (float)base, (float)(centerZ + rightZ * halfWidth)}, {(float)(centerX + rightX * halfWidth + riseX), (float)(base + riseY), (float)(centerZ + rightZ * halfWidth + riseZ)}, {(float)(centerX - rightX * halfWidth + riseX), (float)(base + riseY), (float)(centerZ - rightZ * halfWidth + riseZ)}};
    }

    private BoardLayout() {
    }

    public static boolean inRange(double anchorX, double anchorY, double anchorZ, double viewerX, double viewerY, double viewerZ) {
        double x = viewerX - anchorX;
        double y = viewerY - anchorY;
        double z = viewerZ - anchorZ;
        return x * x + y * y + z * z <= 1024.0;
    }

    public static double[] forwardFromYaw(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(yaw), Math.cos(yaw)};
    }

    public static double yawFromForward(double forwardX, double forwardZ) {
        return Math.toDegrees(Math.atan2(-forwardX, forwardZ));
    }

    public static double heading(double anchorX, double anchorZ, double otherX, double otherZ, double facingYaw) {
        double x = otherX - anchorX;
        double z = otherZ - anchorZ;
        if (Math.sqrt(x * x + z * z) < 1.0E-6) {
            return facingYaw;
        }
        return BoardLayout.yawFromForward(x, z);
    }

    public static double cardArtAspect() {
        double artWidth = 138.688f;
        double artHeight = 109.9959945678711;
        return artWidth / artHeight;
    }

    public static double[] forward(double anchorX, double anchorZ, double viewerX, double viewerZ) {
        double x = viewerX - anchorX;
        double z = viewerZ - anchorZ;
        double length = Math.sqrt(x * x + z * z);
        if (length < 0.001) {
            return new double[]{0.0, 1.0};
        }
        return new double[]{x / length, z / length};
    }

    public static double[] right(double forwardX, double forwardZ) {
        return new double[]{-forwardZ, forwardX};
    }

    public static float lateral(int zone) {
        return ((float)zone - 2.0f) * 3.6f;
    }

    public static float[][] cardBox(double centerX, double centerY, double centerZ, double forwardX, double forwardZ, double rightX, double rightZ, boolean defensive) {
        float[][] underside = BoardLayout.cardCorners(centerX, centerY, centerZ, forwardX, forwardZ, rightX, rightZ, defensive);
        float[][] box = new float[8][3];
        for (int corner = 0; corner < 4; ++corner) {
            box[corner] = underside[corner];
            box[corner + 4] = new float[]{underside[corner][0], underside[corner][1] + 0.04f, underside[corner][2]};
        }
        return box;
    }

    public static float[][] cardCorners(double centerX, double centerY, double centerZ, double forwardX, double forwardZ, double rightX, double rightZ, boolean defensive) {
        double lean = Math.toRadians(0.0);
        double topX = defensive ? rightX : -forwardX;
        double topZ = defensive ? rightZ : -forwardZ;
        double acrossX = defensive ? forwardX : rightX;
        double acrossZ = defensive ? forwardZ : rightZ;
        double longX = topX * Math.cos(lean) * 1.0;
        double longY = Math.sin(lean) * 1.0;
        double longZ = topZ * Math.cos(lean) * 1.0;
        double sideX = acrossX * (double)0.686f;
        double sideZ = acrossZ * (double)0.686f;
        return new float[][]{{(float)(centerX - sideX - longX), (float)(centerY - longY), (float)(centerZ - sideZ - longZ)}, {(float)(centerX + sideX - longX), (float)(centerY - longY), (float)(centerZ + sideZ - longZ)}, {(float)(centerX + sideX + longX), (float)(centerY + longY), (float)(centerZ + sideZ + longZ)}, {(float)(centerX - sideX + longX), (float)(centerY + longY), (float)(centerZ - sideZ + longZ)}};
    }
}

