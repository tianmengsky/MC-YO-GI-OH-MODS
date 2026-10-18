package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.DuelScreen;
import com.kiki.yugioh.network.*;
import dev.codex.yugiohmdpro.client.CardBackTextures;
import dev.codex.yugiohmdpro.client.NativeDuelScreen;
import dev.codex.yugiohmdpro.visual.client.DuelScreenHolograms;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value=DuelScreen.class,remap=false)
public abstract class DuelScreenMixin {
    @Shadow private S2CDuelStatePacket state;
    @Shadow private float scale;
    @Shadow private int left;
    @Unique private boolean mdpro$zones;

    /**
     * Redirects the duel board's own card-back lookup to this addon's artwork. {@code DuelScreen.load}
     * hardcodes the {@code yugioh} namespace, so the resource location is replaced at construction
     * rather than by shipping a file at the same path and hoping this pack wins.
     */
    @Redirect(method="load",at=@At(value="NEW",target="(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"),require=0)
    private static ResourceLocation mdpro$cardBack(String namespace,String path) {
        return CardBackTextures.isLegacyBack(path)?CardBackTextures.CARD_BACK:new ResourceLocation(namespace,path);
    }

    @Inject(method="rebuildButtons",at=@At("HEAD"),cancellable=true)
    private void mdpro$noOldActions(CallbackInfo ci) {if((Object)this instanceof NativeDuelScreen)ci.cancel();}
    @Inject(method="renderButtons",at=@At("HEAD"),cancellable=true)
    private void mdpro$actions(GuiGraphics g,int mx,int my,CallbackInfo ci) {
        if((Object)this instanceof NativeDuelScreen nativeScreen) {nativeScreen.renderActions(g,mx,my);ci.cancel();}
    }
    @Inject(method="showActivationPrompt",at=@At("HEAD"),cancellable=true)
    private void mdpro$noOldPrompt(CallbackInfoReturnable<Boolean> ci) {if((Object)this instanceof NativeDuelScreen)ci.setReturnValue(false);}
    @Inject(method="currentHint",at=@At("HEAD"),cancellable=true)
    private void mdpro$hint(CallbackInfoReturnable<Component> ci) {
        if((Object)this instanceof NativeDuelScreen s)ci.setReturnValue(Component.literal(s.hint()));
    }
    @Inject(method="renderLog",at=@At("HEAD"),cancellable=true)
    private void mdpro$log(GuiGraphics g,CallbackInfo ci) {
        if((Object)this instanceof NativeDuelScreen s){s.renderNativeLog(g);ci.cancel();}
    }
    /**
     * Drops the original hover tooltip; the addon draws the same Minecraft item tooltip itself.
     *
     * <p>The original runs inside the scaled board pose and positions the box at the pointer, which put
     * it far away from the card, and it is skipped entirely while a card list is open, so the pile lists
     * had no tooltip at all. {@code NativeDuelScreen.renderNativeTooltip} replaces it with the same
     * tooltip anchored to the hovered card, for board, hand and list alike.</p>
     */
    @Inject(method="renderHoverTooltip",at=@At("HEAD"),cancellable=true)
    private void mdpro$hoverTooltip(GuiGraphics g,int mx,int my,CallbackInfo ci) {
        if((Object)this instanceof NativeDuelScreen)ci.cancel();
    }
    @Inject(method="renderZones",at=@At("HEAD"))
    private void mdpro$begin(GuiGraphics g,int mx,int my,CallbackInfo ci) {mdpro$zones=true;DuelScreenHolograms.beginFrame();}
    @Inject(method="renderZones",at=@At("TAIL"))
    private void mdpro$end(GuiGraphics g,int mx,int my,CallbackInfo ci) {mdpro$zones=false;DuelScreenHolograms.endFrame();}
    @ModifyVariable(method="drawCard",at=@At("HEAD"),argsOnly=true)
    private CardView mdpro$faceDown(CardView card) {
        if((Object)this instanceof NativeDuelScreen && mdpro$zones && card.position().isFaceDown())
            return new CardView(card.present(),"",card.uid(),card.position(),0,0,false,false,false,card.rarity());
        return card;
    }
    @Inject(method="drawCard",at=@At("TAIL"))
    private void mdpro$projection(GuiGraphics g,int x,int y,int w,int h,CardView card,CallbackInfo ci) {
        if(!((Object)this instanceof NativeDuelScreen) || !mdpro$zones || !card.present())return;
        if(state.self().monsters().stream().anyMatch(c->c.present() && c.uid()==card.uid()) || state.opponent().monsters().stream().anyMatch(c->c.present() && c.uid()==card.uid()))
            DuelScreenHolograms.draw(g,x,y,w,h,card);
        dev.codex.yugiohfix.client.CounterStars.draw(g,x,y,dev.codex.yugiohfix.client.ClientDuelPublicInfo.current().counters().getOrDefault(card.uid(),0));
    }
    @Inject(method="renderPlayerBar",at=@At("HEAD"),cancellable=true)
    private void mdpro$names(GuiGraphics g,SideView side,int y,boolean mine,CallbackInfo ci) {
        if(!((Object)this instanceof NativeDuelScreen))return;
        var font=net.minecraft.client.Minecraft.getInstance().font;
        g.fill(left,y,left+228,y+14,mine?0x8020208C:0x808C2040);
        g.drawString(font,side.playerName(),left+4,y+3,0xFFFFFF,false);
        if(!mine)g.drawCenteredString(font,Component.translatable("gui.yugioh_interaction_fix.hand_count",side.hand().size()),left+114,y+3,0xF2E3B8);
        String lp="LP "+dev.codex.yugiohfix.client.ClientLifePoints.displayed(mine,side.lifePoints());
        g.drawString(font,lp,left+224-font.width(lp),y+3,0xE8F0CC,false);
        ci.cancel();
    }
    @Inject(method="renderBoard",at=@At(value="INVOKE",target="Lcom/kiki/yugioh/client/screen/DuelScreen;renderHint(Lnet/minecraft/client/gui/GuiGraphics;)V"))
    private void mdpro$puppets(GuiGraphics g,int mx,int my,CallbackInfo ci) {
        if(!((Object)this instanceof NativeDuelScreen))return;
        var window=net.minecraft.client.Minecraft.getInstance().getWindow();
        dev.codex.yugiohfix.client.DuelPuppets.beginFrame();
        dev.codex.yugiohfix.client.DuelPuppets.draw(g,scale,Math.round(window.getGuiScaledWidth()/scale),Math.round(window.getGuiScaledHeight()/scale),
                dev.codex.yugiohfix.client.ClientLifePoints.displayed(true,state.self().lifePoints()),
                dev.codex.yugiohfix.client.ClientLifePoints.displayed(false,state.opponent().lifePoints()));
        dev.codex.yugiohfix.client.DuelPuppets.endFrame();g.flush();
        com.mojang.blaze3d.systems.RenderSystem.clear(256,net.minecraft.client.Minecraft.ON_OSX);
    }
}
