package dev.codex.yugiohmdpro.client;

import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Writes the container screen's origin and size without a Mixin refmap.
 *
 * <p>The addon does not generate a refmap, so annotation-based shadow/accessor names would have to be
 * spelled in SRG form for the shipped jar and in official form for the development client. Resolving
 * the fields reflectively with both spellings keeps one code path for both. The values are read every
 * frame by vanilla, so they are not constants that the JIT could fold away.</p>
 */
public final class ScreenFields {
    private static final Field LEFT=field("leftPos","f_97735_");
    private static final Field TOP=field("topPos","f_97736_");
    private static final Field WIDTH=field("imageWidth","f_97726_");
    private static final Field HEIGHT=field("imageHeight","f_97727_");

    private ScreenFields() {}

    /** Places the screen at 0,0 and makes it cover the window, so layouts can use screen coordinates. */
    public static boolean absoluteOrigin(AbstractContainerScreen<?> screen) {
        if(LEFT==null||TOP==null||WIDTH==null||HEIGHT==null)return false;
        try {
            LEFT.setInt(screen,0);
            TOP.setInt(screen,0);
            WIDTH.setInt(screen,screen.width);
            HEIGHT.setInt(screen,screen.height);
            return true;
        }catch(ReflectiveOperationException refused) {
            return false;
        }
    }

    private static Field field(String... names) {
        for(String name:names) {
            try {
                Field found=AbstractContainerScreen.class.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            }catch(NoSuchFieldException missing) {
                // try the next spelling
            }
        }
        return null;
    }
}
