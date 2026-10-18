// Display port from the frozen yugioh-interaction-fix-0.103.2 input.
/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.kiki.yugioh.card.CardDefinition
 *  com.kiki.yugioh.client.ClientDuelHandler
 *  com.kiki.yugioh.client.texture.CardArtTextures
 *  com.kiki.yugioh.network.CardView
 *  com.kiki.yugioh.network.S2CDuelStatePacket
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.mojang.blaze3d.vertex.PoseStack$Pose
 *  com.mojang.blaze3d.vertex.VertexConsumer
 *  com.mojang.math.Axis
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.MultiBufferSource
 *  net.minecraft.client.renderer.MultiBufferSource$BufferSource
 *  net.minecraft.client.renderer.RenderType
 *  net.minecraft.client.renderer.texture.OverlayTexture
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.phys.Vec3
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.client.event.RenderLevelStageEvent
 *  net.minecraftforge.client.event.RenderLevelStageEvent$Stage
 *  net.minecraftforge.eventbus.api.SubscribeEvent
 *  net.minecraftforge.fml.common.Mod$EventBusSubscriber
 *  net.minecraftforge.fml.common.Mod$EventBusSubscriber$Bus
 */
package dev.codex.yugiohmdpro.visual.client;

import com.kiki.yugioh.card.CardDefinition;
import com.kiki.yugioh.client.ClientDuelHandler;
import com.kiki.yugioh.client.texture.CardArtTextures;
import com.kiki.yugioh.network.CardView;
import com.kiki.yugioh.network.S2CDuelStatePacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.codex.yugiohmdpro.visual.board.BoardLayout;
import dev.codex.yugiohmdpro.visual.board.BoardPlan;
import dev.codex.yugiohmdpro.visual.board.HologramMotion;
import dev.codex.yugiohmdpro.visual.client.ClientBoardAnchor;
import dev.codex.yugiohmdpro.visual.client.HologramActions;
import dev.codex.yugiohmdpro.visual.client.HologramArt;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon", value={Dist.CLIENT}, bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class InWorldBoardRenderer {
    private static final float EDGE_LOW = 0.02f;
    private static final float EDGE_HIGH = 0.05f;
    private static final int HOLOGRAM_ALPHA = 190;
    private static final float HOVER_BLOCKS = 0.12f;

    private InWorldBoardRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        for(var board:PublicProjections.current(minecraft.level.dimension().location().toString())) {
            if(!BoardLayout.inRange(board.x,board.y,board.z,minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ()))continue;
            HologramActions.inContext(board.animations,()->renderBoard(event,board));
        }
    }

    /** Both duelists and bystanders use the same public, dimension-scoped world board. */
    private static void renderBoard(RenderLevelStageEvent event,PublicProjections.Board board) {
        Minecraft minecraft=Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        drawBoard(pose,buffers,board);
        pose.popPose();
        buffers.endBatch();
    }

    static int drawBoard(PoseStack pose,MultiBufferSource buffers,PublicProjections.Board board) {
        double[] forward=BoardLayout.forwardFromYaw(board.yaw);
        double forwardX=forward[0],forwardZ=forward[1],rightX=-forwardZ,rightZ=forwardX;
        List<BoardPlan.Slot> slots=board.slots();
        for (BoardPlan.Slot slot : slots) {
            InWorldBoardRenderer.drawCard(pose,buffers,slot,forwardX,forwardZ,rightX,rightZ);
        }
        return slots.size();
    }

    private static void drawCard(PoseStack pose, MultiBufferSource buffers, BoardPlan.Slot slot, double forwardX, double forwardZ, double rightX, double rightZ) {
        ResourceLocation art = slot.sleeve() || slot.card().definition() == null ? CardArtTextures.back() : CardArtTextures.front((CardDefinition)slot.card().definition());
        ResourceLocation sleeve = CardArtTextures.back();
        float[][] box = slot.box();
        PoseStack.Pose last = pose.last();
        VertexConsumer top = buffers.getBuffer(RenderType.entityCutoutNoCull((ResourceLocation)art));
        InWorldBoardRenderer.quad(last, top, 0.0, 1.0, 0.0, box[7], 0.0f, 0.0f, box[6], 1.0f, 0.0f, box[5], 1.0f, 1.0f, box[4], 0.0f, 1.0f);
        VertexConsumer back = buffers.getBuffer(RenderType.entityCutoutNoCull((ResourceLocation)sleeve));
        InWorldBoardRenderer.quad(last, back, 0.0, -1.0, 0.0, box[0], 0.0f, 1.0f, box[1], 1.0f, 1.0f, box[2], 1.0f, 0.0f, box[3], 0.0f, 0.0f);
        InWorldBoardRenderer.quad(last, back, forwardX, 0.0, forwardZ, box[1], 0.02f, 0.05f, box[0], 0.05f, 0.05f, box[4], 0.05f, 0.02f, box[5], 0.02f, 0.02f);
        InWorldBoardRenderer.quad(last, back, -forwardX, 0.0, -forwardZ, box[3], 0.02f, 0.05f, box[2], 0.05f, 0.05f, box[6], 0.05f, 0.02f, box[7], 0.02f, 0.02f);
        InWorldBoardRenderer.quad(last, back, -rightX, 0.0, -rightZ, box[0], 0.02f, 0.05f, box[3], 0.05f, 0.05f, box[7], 0.05f, 0.02f, box[4], 0.02f, 0.02f);
        InWorldBoardRenderer.quad(last, back, rightX, 0.0, rightZ, box[2], 0.02f, 0.05f, box[1], 0.05f, 0.05f, box[5], 0.05f, 0.02f, box[6], 0.02f, 0.02f);
        if (slot.projection() != null) {
            float v1;
            float v0;
            float u1;
            float u0;
            ResourceLocation texture;
            double aspect;
            float[] at = slot.projection();
            ResourceLocation monster = HologramArt.of(slot.card().cardId());
            if (monster != null) {
                int[] size = CardArtTextures.dimensionsOf((ResourceLocation)monster);
                aspect = size[1] <= 0 ? 1.0 : (double)size[0] / (double)size[1];
                texture = monster;
                u0 = 0.0f;
                u1 = 1.0f;
                v0 = 0.0f;
                v1 = 1.0f;
            } else {
                aspect = BoardLayout.cardArtAspect();
                texture = art;
                u0 = 0.1f;
                u1 = 0.888f;
                v0 = 0.216f;
                v1 = 0.644f;
            }
            Minecraft client = Minecraft.getInstance();
            Vec3 eye = client.gameRenderer.getMainCamera().getPosition();
            double towardX = eye.x - (double)at[0];
            double towardZ = eye.z - (double)at[2];
            double length = Math.sqrt(towardX * towardX + towardZ * towardZ);
            if (length < 0.001) {
                towardX = 0.0;
                towardZ = 1.0;
            } else {
                towardX /= length;
                towardZ /= length;
            }
            float bob = InWorldBoardRenderer.hoverBlocks(slot.card());
            float[][] panel = BoardLayout.hologramCorners(at[0], at[1], at[2], towardX, towardZ, aspect, bob);
            VertexConsumer standing = buffers.getBuffer(RenderType.entityTranslucent((ResourceLocation)texture));
            InWorldBoardRenderer.hologram(last, standing, panel, false, towardX, towardZ, u0, u1, v0, v1);
        }
    }

    private static float hoverBlocks(CardView card) {
        double seconds = (Minecraft.getInstance().level == null ? 0.0 : (double)Minecraft.getInstance().level.getGameTime()) / 20.0;
        return (float)(Math.sin(HologramMotion.hoverPhase(seconds, card.uid())) * (double)0.12f);
    }

    private static void hologram(PoseStack.Pose pose, VertexConsumer consumer, float[][] panel, boolean mirrored, double normalX, double normalZ, float u0, float u1, float v0, float v1) {
        float left = mirrored ? u1 : u0;
        float right = mirrored ? u0 : u1;
        InWorldBoardRenderer.holo(pose, consumer, panel[0], left, v1, normalX, normalZ);
        InWorldBoardRenderer.holo(pose, consumer, panel[1], right, v1, normalX, normalZ);
        InWorldBoardRenderer.holo(pose, consumer, panel[2], right, v0, normalX, normalZ);
        InWorldBoardRenderer.holo(pose, consumer, panel[3], left, v0, normalX, normalZ);
    }

    private static void holo(PoseStack.Pose pose, VertexConsumer consumer, float[] point, float u, float v, double normalX, double normalZ) {
        consumer.vertex(pose.pose(), point[0], point[1], point[2]).color(255, 255, 255, 190).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(pose.normal(), (float)normalX, 0.0f, (float)normalZ).endVertex();
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, double normalX, double normalY, double normalZ, float[] a, float au, float av, float[] b, float bu, float bv, float[] c, float cu, float cv, float[] d, float du, float dv) {
        InWorldBoardRenderer.vertex(pose, consumer, normalX, normalY, normalZ, a, au, av);
        InWorldBoardRenderer.vertex(pose, consumer, normalX, normalY, normalZ, b, bu, bv);
        InWorldBoardRenderer.vertex(pose, consumer, normalX, normalY, normalZ, c, cu, cv);
        InWorldBoardRenderer.vertex(pose, consumer, normalX, normalY, normalZ, d, du, dv);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, double normalX, double normalY, double normalZ, float[] point, float u, float v) {
        consumer.vertex(pose.pose(), point[0], point[1], point[2]).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(pose.normal(), (float)normalX, (float)normalY, (float)normalZ).endVertex();
    }
}

