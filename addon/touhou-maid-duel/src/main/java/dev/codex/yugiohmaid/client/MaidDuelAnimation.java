package dev.codex.yugiohmaid.client;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoBone;
import dev.codex.yugiohfix.client.PuppetModel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retargets the YGO native opponent pose (the AI seat, {@code mine == false}) onto a Tohou Little
 * Maid skeleton.
 *
 * <p>The native puppet and the Gecko maid share the X pitch (forward/back) and the Y turn; the Z
 * roll is mirrored.  Body, head and legs therefore target {@code (qx, qy, -qz, qw)}.  The four arm
 * bones are the exception: they are copied as the native <em>local</em> rotation, because the native
 * left arm is not the mirror of the right (the summon pose bends the left elbow forward while the
 * right arm is straight up); mirroring the arm world targets sends the offhand disk behind the body.
 * The earlier all-bone {@code (qx,-qy,-qz)} made the root face screen right instead of the board;
 * Y has to stay positive for the body.  The local rotation is solved in quaternion space after this
 * conversion.</p>
 *
 * <p>The puppet and the maid also have different parent chains (the puppet legs are children of
 * {@code all}, the winefox legs are children of {@code AllBody}), so copying the native local
 * rotation is not enough either.  The target is a <em>world</em> orientation: each mapped maid bone
 * receives {@code inverse(parentWorld) * mirroredNativeWorld}.  Helper bones between two mapped
 * joints are put at their bind rotation first, so the solved local rotation is stable and the
 * humanoid chain is exact while hair, ears, tail, cloth and locators keep their own TLM animation.
 * Unmapped bones are never touched.</p>
 */
public final class MaidDuelAnimation implements AutoCloseable {
    private static final String[][] GECKO = {
            {"all", "Root"}, {"Body", "AllBody"}, {"Body2", "UpBody"}, {"Head", "Head"},
            {"Right Arm", "RightArm"}, {"Right_Arm2", "RightForeArm"},
            {"Left Arm", "LeftArm"}, {"armorLeftArm5", "LeftForeArm"},
            {"Right Leg", "RightLeg"}, {"Right_Leg2", "RightLowerLeg"},
            {"Left Leg", "LeftLeg"}, {"Left_Leg2", "LeftLowerLeg"}
    };
    private static final String[][] CLASSIC = {
            {"Head", "head"}, {"Body2", "body"},
            {"Right Arm", "armRight"}, {"Left Arm", "armLeft"},
            {"Right Leg", "legRight"}, {"Left Leg", "legLeft"}
    };
    private static final String[] CLASSIC_REQUIRED = {"head", "armLeft", "armRight", "legLeft", "legRight"};
    private static final boolean CHECK = true;
    private static boolean reported;
    private static final Set<String> skipped = new HashSet<>();
    private static MaidDuelAnimation current;
    private final MaidDuelAnimation previous;
    private final EntityMaid entity;
    private final PuppetModel source;
    private final float[] pose;
    private final boolean idle;
    private final Map<String, Integer> indices = new HashMap<>();
    private final Map<Object, Runnable> restore = new IdentityHashMap<>();
    private final Matrix4f matrix = new Matrix4f();
    private final Vector3f euler = new Vector3f();

    private MaidDuelAnimation(EntityMaid entity, PuppetModel source, float[] pose, boolean idle) {
        this.previous = current;
        this.entity = entity;
        this.source = source;
        this.pose = pose;
        this.idle = idle;
        for (int i = 0; i < source.boneNames.length; i++) indices.put(source.boneNames[i], i);
        current = this;
    }

    public static MaidDuelAnimation open(EntityMaid entity, PuppetModel source, float[] pose, boolean idle) {
        if (pose.length != source.boneNames.length * 10) throw new IllegalArgumentException("Invalid YGO pose");
        return new MaidDuelAnimation(entity, source, pose, idle);
    }

    private static boolean targets(IMaid maid) {
        return current != null && current.entity != null && maid.asEntity() == current.entity && !maid.isYsmModel();
    }

    public static void gecko(IMaid maid, AnimatedGeoModel model) {
        if (targets(maid)) current.applyGecko(model);
    }

    public static void bedrock(IMaid maid, HashMap<String, ModelRendererWrapper> parts) {
        if (targets(maid)) current.applyBedrock(parts);
    }

    /**
     * Winefox and its derivatives.  A rig is accepted only when every source and target bone exists:
     * a half-mapped humanoid would look worse than the model's own animation.
     */
    public boolean applyGecko(AnimatedGeoModel model) {
        Map<String, AnimatedGeoBone> bones = model.bones();
        for (String[] pair : GECKO) {
            if (!bones.containsKey(pair[1])) return skip("target:" + pair[1]);
            if (!indices.containsKey(pair[0])) return skip("source:" + pair[0]);
        }
        Quaternionf[] natives = nativeWorlds();
        Set<String> mapped = new HashSet<>();
        for (String[] pair : GECKO) mapped.add(pair[1]);
        // Bind the helper joints on the humanoid chain first.  Their own idle sway would otherwise
        // add up on top of the solved orientation of the mapped child.
        for (String[] pair : GECKO) {
            GeoBone parent = bones.get(pair[1]).geoBone().parent();
            while (parent != null && !mapped.contains(parent.name())) {
                AnimatedGeoBone helper = bones.get(parent.name());
                if (helper == null) break;
                save(helper);
                Vector3f rest = helper.geoBone().rotation();
                helper.setRotationX(rest.x);
                helper.setRotationY(rest.y);
                helper.setRotationZ(rest.z);
                helper.setPosition(0, 0, 0);
                helper.setScale(1, 1, 1);
                parent = parent.parent();
            }
        }
        List<String[]> order = new ArrayList<>(List.of(GECKO));
        order.sort(Comparator.comparingInt(pair -> depth(bones, bones.get(pair[1]))));
        float error = 0;
        for (String[] pair : order) {
            AnimatedGeoBone bone = bones.get(pair[1]);
            Quaternionf parent = parentWorld(bones, bone);
            Quaternionf wanted = mirror(natives[indices.get(pair[0])]);
            Quaternionf local = parent.invert().mul(wanted).normalize();
            setRotation(bone, local);
            boolean arm = isArmSource(pair[0]);
            // Arms are same-side chains, and the native left arm is not the mirror of the right
            // (the summon pose bends the left elbow forward while the right arm is straight up).
            // Copy the native local rotation for the arms; mirroring the world target here sends
            // the offhand disk behind the body.  Body/head/legs still use the mirrored world.
            if (arm) setRotation(bone, local(pair[0]));
            bone.setPosition(0, 0, 0);
            bone.setScale(1, 1, 1);
            if (CHECK && !arm) error = Math.max(error, degrees(parentWorld(bones, bone).mul(localQuat(bone)), wanted));
        }
        if (CHECK) report(error);
        return true;
    }

    private static boolean skip(String bone) {
        if (skipped.add(bone)) {
            org.slf4j.LoggerFactory.getLogger("touhou_maid_duel").warn("MAID_RETARGET_SKIP {}", bone);
        }
        return false;
    }

    private static void report(float error) {
        if (reported) return;
        reported = true;
        org.slf4j.LoggerFactory.getLogger("touhou_maid_duel")
                .info("MAID_RETARGET_READY maxError={} deg", error);
    }

    private static float degrees(Quaternionf a, Quaternionf b) {
        Quaternionf delta = new Quaternionf(a).normalize().invert().mul(b).normalize();
        return (float) Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs(delta.w))));
    }

    /** Native world rotations, indexed like {@link PuppetModel#boneNames}. */
    private Quaternionf[] nativeWorlds() {
        int count = source.boneNames.length;
        Quaternionf[] world = new Quaternionf[count];
        for (int i = 0; i < count; i++) world[i] = nativeWorld(i, world);
        return world;
    }

    private Quaternionf nativeWorld(int bone, Quaternionf[] world) {
        if (world[bone] != null) return world[bone];
        Quaternionf local = local(source.boneNames[bone]);
        int parent = source.parents[bone];
        // Never multiply into the cached parent world: that aliases and corrupts every ancestor.
        world[bone] = parent < 0 ? local
                : new Quaternionf(nativeWorld(parent, world)).mul(local).normalize();
        return world[bone];
    }

    private static int depth(Map<String, AnimatedGeoBone> bones, AnimatedGeoBone bone) {
        int depth = 0;
        for (GeoBone parent = bone.geoBone().parent(); parent != null; parent = parent.parent()) depth++;
        return depth;
    }

    /** Current world rotation of the bone's parent, after bind and already solved siblings. */
    private static Quaternionf parentWorld(Map<String, AnimatedGeoBone> bones, AnimatedGeoBone bone) {
        GeoBone parent = bone.geoBone().parent();
        if (parent == null) return new Quaternionf();
        AnimatedGeoBone current = bones.get(parent.name());
        if (current == null) return new Quaternionf();
        Quaternionf world = localQuat(current);
        for (GeoBone ancestor = parent.parent(); ancestor != null; ancestor = ancestor.parent()) {
            AnimatedGeoBone boneAbove = bones.get(ancestor.name());
            if (boneAbove == null) break;
            world = localQuat(boneAbove).mul(world);
        }
        return world;
    }

    private static Quaternionf localQuat(AnimatedGeoBone bone) {
        return new Quaternionf().rotationZYX(bone.getRotationZ(), bone.getRotationY(), bone.getRotationX()).normalize();
    }

    private void setRotation(AnimatedGeoBone bone, Quaternionf rotation) {
        save(bone);
        matrix.rotation(rotation);
        matrix.getEulerAnglesZYX(euler);
        bone.setRotationX(euler.x);
        bone.setRotationY(euler.y);
        bone.setRotationZ(euler.z);
    }

    /** The Gecko builder mirrors Bedrock rotations with {@code Rz(pi) * q * Rz(-pi)}. */
    private static boolean isArmSource(String source) {
        return source.equals("Left Arm") || source.equals("armorLeftArm5")
                || source.equals("Right Arm") || source.equals("Right_Arm2");
    }

    private static Quaternionf mirror(Quaternionf rotation) {
        return new Quaternionf(rotation.x, rotation.y, -rotation.z, rotation.w).normalize();
    }

    private Quaternionf local(String name) {
        Integer index = indices.get(name);
        if (index == null) return new Quaternionf();
        int offset = index * 10;
        return new Quaternionf(pose[offset], pose[offset + 1], pose[offset + 2], pose[offset + 3]).normalize();
    }

    private void save(AnimatedGeoBone bone) {
        restore.computeIfAbsent(bone, key -> {
            float x = bone.getRotationX(), y = bone.getRotationY(), z = bone.getRotationZ();
            float px = bone.getPositionX(), py = bone.getPositionY(), pz = bone.getPositionZ();
            float sx = bone.getScaleX(), sy = bone.getScaleY(), sz = bone.getScaleZ();
            return () -> {
                bone.setRotationX(x); bone.setRotationY(y); bone.setRotationZ(z);
                bone.setPositionX(px); bone.setPositionY(py); bone.setPositionZ(pz);
                bone.setScaleX(sx); bone.setScaleY(sy); bone.setScaleZ(sz);
            };
        });
    }

    private void save(ModelRendererWrapper part) {
        restore.computeIfAbsent(part.getModelRenderer(), key -> {
            float x = part.getRotateAngleX(), y = part.getRotateAngleY(), z = part.getRotateAngleZ();
            return () -> { part.setRotateAngleX(x); part.setRotateAngleY(y); part.setRotateAngleZ(z); };
        });
    }

    /**
     * Classic Bedrock rigs keep independent head/arm/leg roots, so there is no hierarchy to correct.
     * The native world quaternion is written to each part with the Bedrock sign convention; models
     * without the full part set keep their own animation.
     */
    public boolean applyBedrock(Map<String, ModelRendererWrapper> parts) {
        for (String part : CLASSIC_REQUIRED) {
            if (!parts.containsKey(part)) return false;
        }
        Quaternionf[] natives = nativeWorlds();
        for (String[] pair : CLASSIC) {
            ModelRendererWrapper part = parts.get(pair[1]);
            if (part == null || !indices.containsKey(pair[0])) continue;
            Quaternionf q = mirror(natives[indices.get(pair[0])]);
            matrix.rotation(q);
            matrix.getEulerAnglesZYX(euler);
            save(part);
            part.setRotateAngleX(-euler.x);
            part.setRotateAngleY(-euler.y);
            part.setRotateAngleZ(euler.z);
        }
        return true;
    }

    @Override public void close() {
        try { for (Runnable action : restore.values()) action.run(); }
        finally { current = previous; }
    }
}
