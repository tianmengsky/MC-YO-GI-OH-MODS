package dev.codex.yugiohmaid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.codex.yugiohfix.board.BoardLayout;
import dev.codex.yugiohfix.board.BoardPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Temporary server-side lock with saved recovery data for a world saved during a match. */
public final class MaidDuelPositionState {
    private static final String RECOVERY = "touhou_maid_duel:position_recovery";
    private final EntityMaid maid;
    private final Vec3 originalPosition;
    private final boolean originalNoAi;
    private final boolean originalNoGravity;
    private final float originalYaw;
    private final float originalHead;
    private final float originalBody;
    private Vec3 safePosition;
    private final Vec3 facePosition;

    private MaidDuelPositionState(EntityMaid maid, Vec3 safePosition, Vec3 facePosition) {
        this.maid = maid;
        this.originalPosition = maid.position();
        this.originalNoAi = maid.isNoAi();
        this.originalNoGravity = maid.isNoGravity();
        this.originalYaw = maid.getYRot();
        this.originalHead = maid.getYHeadRot();
        this.originalBody = maid.yBodyRot;
        this.safePosition = safePosition;
        this.facePosition = facePosition;
    }

    public static MaidDuelPositionState capture(EntityMaid maid, ServerPlayer player) {
        double[] forward = BoardLayout.forwardFromYaw(player.getYRot());
        double[] far = BoardPlan.opponentStand(player.getX(), player.getZ(), forward[0], forward[1]);
        Vec3 preferred = new Vec3(far[0], player.getY(), far[1]);
        Vec3 safe = findSafePosition(maid, preferred, 3);
        if (safe == null) safe = findSafePosition(maid, maid.position(), 3);
        return safe == null ? null : new MaidDuelPositionState(maid, safe, player.position());
    }

    public java.util.UUID maidId() { return maid.getUUID(); }

    public void freeze() {
        CompoundTag saved = new CompoundTag();
        saved.putDouble("x", originalPosition.x);
        saved.putDouble("y", originalPosition.y);
        saved.putDouble("z", originalPosition.z);
        saved.putString("dimension", maid.level().dimension().location().toString());
        saved.putBoolean("noAi", originalNoAi);
        saved.putBoolean("noGravity", originalNoGravity);
        saved.putFloat("yaw", originalYaw);
        saved.putFloat("head", originalHead);
        saved.putFloat("body", originalBody);
        maid.getPersistentData().put(RECOVERY, saved);
        pin();
    }

    public boolean maintain(ServerPlayer player) {
        if (!maid.isAlive() || maid.isRemoved() || maid.level() != player.level()
                || maid.isPassenger() || maid.isVehicle()) return false;
        if (!isSafe(maid, safePosition)) {
            Vec3 replacement = findSafePosition(maid, safePosition, 3);
            if (replacement == null) return false;
            safePosition = replacement;
        }
        pin();
        return true;
    }

    private void pin() {
        maid.setNoAi(true);
        maid.setNoGravity(true);
        maid.getNavigation().stop();
        maid.setTarget(null);
        maid.setDeltaMovement(Vec3.ZERO);
        maid.fallDistance = 0;
        // Correct pushes and knockback as well as voluntary movement.
        maid.setPos(safePosition.x, safePosition.y, safePosition.z);
        float yaw = (float)Math.toDegrees(Math.atan2(-(facePosition.x - safePosition.x), facePosition.z - safePosition.z));
        maid.setYRot(yaw);
        maid.setYHeadRot(yaw);
        maid.setYBodyRot(yaw);
    }

    public void restore() { recover(maid); }

    /** Also used after restart/chunk reload when no duel session survives. */
    public static void recover(EntityMaid maid) {
        if (!maid.getPersistentData().contains(RECOVERY)) return;
        CompoundTag saved = maid.getPersistentData().getCompound(RECOVERY);
        Vec3 original = new Vec3(saved.getDouble("x"), saved.getDouble("y"), saved.getDouble("z"));
        boolean sameDimension = saved.getString("dimension").equals(maid.level().dimension().location().toString());
        Vec3 target = sameDimension ? (isSafe(maid, original) ? original : findSafePosition(maid, original, 3)) : null;
        if (target == null) target = findSafePosition(maid, maid.position(), 6);
        if (target != null) maid.setPos(target.x, target.y, target.z);
        maid.setNoAi(saved.getBoolean("noAi"));
        maid.setNoGravity(saved.getBoolean("noGravity"));
        maid.setYRot(saved.getFloat("yaw"));
        maid.setYHeadRot(saved.getFloat("head"));
        maid.setYBodyRot(saved.getFloat("body"));
        maid.setDeltaMovement(Vec3.ZERO);
        maid.fallDistance = 0;
        maid.getNavigation().stop();
        maid.getPersistentData().remove(RECOVERY);
    }

    private static Vec3 findSafePosition(EntityMaid maid, Vec3 center, int range) {
        if (isSafe(maid, center)) return center;
        BlockPos origin = BlockPos.containing(center);
        // Prefer the same height, then nearby levels, nearest ring first.
        for (int radius = 0; radius <= range; radius++) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        Vec3 candidate = Vec3.atBottomCenterOf(origin.offset(dx, dy, dz));
                        if (isSafe(maid, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSafe(EntityMaid maid, Vec3 candidate) {
        var level = maid.level();
        AABB box = maid.getBoundingBox().move(candidate.subtract(maid.position()));
        if (box.minY < level.getMinBuildHeight() || box.maxY >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box)
                || !level.hasChunksAt(BlockPos.containing(box.minX, box.minY - 1, box.minZ),
                                      BlockPos.containing(box.maxX, box.maxY, box.maxZ))) return false;
        if (!level.noCollision(maid, box) || level.containsAnyLiquid(box)) return false;
        AABB support = new AABB(box.minX + .05, box.minY - .1, box.minZ + .05,
                box.maxX - .05, box.minY, box.maxZ - .05);
        if (!level.getBlockCollisions(maid, support).iterator().hasNext()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY - .1, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var state = level.getBlockState(pos);
            if (!level.getFluidState(pos).isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return true;
    }
}
