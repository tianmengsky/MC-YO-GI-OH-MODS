package dev.codex.yugiohmdpro.visual.client;

import dev.codex.yugiohmdpro.visual.board.BoardLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance fixture for the standing hologram panel: the drawn artwork must keep
 * the aspect ratio of its texture. It measures both the addon layout and the frozen interaction-fix
 * layout that the 0.6.13 release actually renders monsters with.
 */
@Mod.EventBusSubscriber(modid="yugioh_mdpro_addon",value=Dist.CLIENT)
public final class ProjectionAspectSmoke {
    private static final boolean ENABLED=Boolean.getBoolean("yugioh.mdpro.visualSmoke");
    /** Real reviewed cutout sizes: square, 300x318 and the 176x257 card art window. */
    private static final double[] ASPECTS={1.0,300.0/318.0,176.0/257.0,138.688/109.996};
    private static boolean checked;

    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED||checked)return;
        checked=true;
        Minecraft minecraft=Minecraft.getInstance();
        try {
            List<String> lines=new ArrayList<>();
            for(double aspect:ASPECTS) {
                lines.add(measure("addon",BoardLayout.hologramCorners(0,0,0,0,1,aspect,0),aspect));
                lines.add(measure("frozen",dev.codex.yugiohfix.board.BoardLayout.hologramCorners(0,0,0,0,1,aspect,0),aspect));
            }
            StringBuilder text=new StringBuilder("站立立绘面板宽高比：宽/高 必须等于贴图宽高比\n");
            for(String line:lines)text.append("  ").append(line).append('\n');
            Files.writeString(result(),text.toString());
            LoggerFactory.getLogger("MDProProjectionSmoke").info("Hologram panel aspect verified: {}",String.join("; ",lines));
        }catch(Throwable error) {
            try {Files.writeString(FMLPaths.GAMEDIR.get().resolve("../generated/visual-smoke-result.txt").normalize(),"FAIL: projection fixture: "+error);}catch(Exception ignored) {}
            LoggerFactory.getLogger("MDProProjectionSmoke").error("Hologram panel fixture failed",error);
            minecraft.stop();
        }
    }

    private static String measure(String who,float[][] panel,double aspect) {
        double width=distance(panel[0],panel[1]);
        double height=distance(panel[1],panel[2]);
        double drawn=width/height;
        double error=Math.abs(drawn-aspect)/aspect;
        if(error>0.02)throw new IllegalStateException(who+" hologram panel aspect "+round(drawn)+" != artwork aspect "+round(aspect));
        return who+" aspect="+round(aspect)+" panel="+round(width)+"x"+round(height)+" drawn="+round(drawn);
    }

    private static double distance(float[] a,float[] b) {
        double x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];
        return Math.sqrt(x*x+y*y+z*z);
    }

    private static String round(double value) {return String.format(java.util.Locale.ROOT,"%.4f",value);}

    private static Path result() {return FMLPaths.GAMEDIR.get().resolve("../generated/projection-aspect.txt").normalize();}
}
