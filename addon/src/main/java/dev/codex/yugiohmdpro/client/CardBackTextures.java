package dev.codex.yugiohmdpro.client;

import net.minecraft.resources.ResourceLocation;

/**
 * Where the duel screen's card back comes from.
 *
 * <p>The original mod ships a 59x86 placeholder back. This addon ships the MDPro3 default protector
 * art, downscaled to 128x184 and palette-compressed from 656 KB to 23 KB, under its own namespace.
 * The two lookups that resolve the back are redirected here by mixins, because overriding the
 * {@code yugioh:textures/card/card_back.png} path would depend on mod resource-pack ordering and could
 * silently keep the old art.</p>
 */
public final class CardBackTextures {
    /** The replacement card back, in this addon's namespace. */
    public static final ResourceLocation CARD_BACK=
            new ResourceLocation("yugioh_mdpro_addon","textures/card/card_back.png");
    /** The path the duel board asks for; only this file name is redirected. */
    public static final String LEGACY_FILE="card_back.png";

    private CardBackTextures() {}

    /** True when the original mod is about to load its own card back file. */
    public static boolean isLegacyBack(String path) {
        return path!=null && path.endsWith(LEGACY_FILE);
    }
}
