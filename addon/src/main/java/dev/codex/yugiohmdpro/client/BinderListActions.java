package dev.codex.yugiohmdpro.client;

import dev.codex.yugiohfix.client.BinderClient;
import dev.codex.yugiohfix.client.BinderFilter;
import java.lang.reflect.Field;
import net.minecraft.client.gui.components.EditBox;
import org.slf4j.LoggerFactory;

/**
 * Actions behind the card list's 清空 button.
 *
 * <p>The reviewed search box is a private unique field its own mixin adds to the deck box screen, so it
 * is reached reflectively; the filters and the scroll position belong to the reviewed binder client and
 * are cleared through their public API. Everything is guarded, because none of this may break the
 * screen when the reviewed pieces are absent.</p>
 */
public final class BinderListActions {
    private static final String SEARCH_FIELD="fix$search";

    private BinderListActions() {}

    /** Clears the search text, every filter and the scroll position. Returns true when the box was cleared. */
    public static boolean clear(Object screen) {
        boolean cleared=clearSearch(screen);
        try {
            BinderClient.setNeedle("");
            DeckFilterModel.INSTANCE.reset();
            BinderClient.setStripScroll(0);
        }catch(Throwable unavailable) {
            LoggerFactory.getLogger("MDProDeckEditor").debug("Binder state unavailable while clearing",unavailable);
        }
        return cleared;
    }

    public static EditBox search(Object screen) {
        if(screen==null)return null;
        try {
            for(Class<?> type=screen.getClass();type!=null;type=type.getSuperclass()) {
                try {Field f=type.getDeclaredField(SEARCH_FIELD);f.setAccessible(true);return (EditBox)f.get(screen);}
                catch(NoSuchFieldException next) {}
            }
        }catch(ReflectiveOperationException missing) {}
        return null;
    }
    public static boolean clearSearch(Object screen) {
        EditBox box=search(screen);if(box==null)return false;
        box.setValue("");box.setFocused(false);return true;
    }

    /** Whether any filter is currently narrowing the list. */
    public static boolean filtersActive() {
        try {
            return BinderFilter.active()||DeckFilterModel.INSTANCE.raceActive();
        }catch(Throwable unavailable) {
            return false;
        }
    }
}
