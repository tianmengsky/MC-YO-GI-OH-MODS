package dev.codex.yugiohmdpro;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Administrative diagnostics use a child JVM, so a native crash cannot kill Minecraft. */
@Mod.EventBusSubscriber(modid = MdproAddon.MOD_ID)
public final class EngineCommands {
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mdpro-verifier"); thread.setDaemon(true); return thread;
    });

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mdpro")
                .then(Commands.literal("open").executes(context -> {
                    var player=context.getSource().getPlayerOrException();
                    var duel=com.kiki.yugioh.duel.DuelManager.get(player.getServer()).duelOf(player.getUUID());
                    if(duel==null) {context.getSource().sendFailure(Component.literal("当前没有正在进行的决斗。"));return 0;}
                    dev.codex.yugiohmdpro.duel.DuelBridge.open(duel,player); return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "MDPro3 / mdpromod 示例宿主：独立进程决斗与 Windbot；大师规则 3。诊断验证=" + (running.get() ? "进行中" : "空闲")
                                    + "；对局演出节奏=" + dev.codex.yugiohmdpro.duel.DuelBridge.pacing()), false);
                    return 1;
                }))
                // Presentation speed for every duel on this server. OP only: one setting is global.
                .then(Commands.literal("pace").requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "当前对局演出节奏：" + dev.codex.yugiohmdpro.duel.DuelBridge.pacing()
                                            + "。可选 slow / normal / fast / instant（instant 为不节拍的旧行为）。"), false);
                            return 1;
                        })
                        .then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(context -> {
                                    String value = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "mode");
                                    dev.codex.yugiohmdpro.duel.DuelBridge.pacing(
                                            dev.codex.yugiohmdpro.duel.DuelPacing.parse(value));
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "对局演出节奏已设为：" + dev.codex.yugiohmdpro.duel.DuelBridge.pacing()
                                                    + "（进行中的对局从下一个动作起生效）"), true);
                                    return 1;
                                })))
                .then(Commands.literal("verify").requires(source -> source.hasPermission(2)).executes(context -> {
                    var source = context.getSource();
                    if (!running.compareAndSet(false, true)) {
                        source.sendFailure(Component.literal("已有 MDPro 验证正在运行。")); return 0;
                    }
                    Path root = Path.of(System.getProperty("yugioh.mdpro.referenceRuntime",
                            FMLPaths.GAMEDIR.get().resolve("mdpro-reference").toString())).toAbsolutePath();
                    source.sendSuccess(() -> Component.literal("正在隔离子进程验证 MDPro 内核与六回合流程。"), false);
                    worker.execute(() -> {
                        String outcome;
                        try {
                            dev.codex.yugiohmdpro.engine.ReferenceProbe.verify(root);
                            outcome = "示例决斗宿主验证通过：MR3、六回合、弃牌与独立进程生命周期。";
                        } catch (Exception ex) { outcome = "MDPro 验证失败：" + ex.getMessage(); }
                        finally { running.set(false); }
                        String message = outcome;
                        source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), false));
                    });
                    return 1;
                })));
    }
}
