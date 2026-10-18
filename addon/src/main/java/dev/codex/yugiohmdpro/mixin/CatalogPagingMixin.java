package dev.codex.yugiohmdpro.mixin;

import com.kiki.yugioh.client.screen.CardCatalogScreen;
import dev.codex.yugiohmdpro.client.CatalogWorkspace;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Own the catalog presentation/input while retaining the original search and server actions. */
@Mixin(value=CardCatalogScreen.class,priority=500,remap=false)
public abstract class CatalogPagingMixin extends Screen {
    protected CatalogPagingMixin(Component title) {super(title);}
    private CardCatalogScreen mdpro$screen() {return (CardCatalogScreen)(Object)this;}
    @Inject(method={"init","m_7856_"},at=@At("RETURN"),remap=false)
    private void mdpro$init(CallbackInfo ci) {CatalogWorkspace.placeSearch(mdpro$screen());}
    @Inject(method={"render","m_88315_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$render(GuiGraphics g,int x,int y,float tick,CallbackInfo ci) {
        renderBackground(g);CatalogWorkspace.render(mdpro$screen(),g,font,x,y,tick);ci.cancel();
    }
    @Inject(method="buildChips",at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$chips(CallbackInfo ci) {((CatalogAccess)this).mdpro$chips().clear();ci.cancel();}
    @Inject(method="panelLeft",at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$left(CallbackInfoReturnable<Integer> ci) {ci.setReturnValue(6);}
    @Inject(method="panelTop",at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$top(CallbackInfoReturnable<Integer> ci) {ci.setReturnValue(6);}
    @Inject(method="headerHeight",at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$header(CallbackInfoReturnable<Integer> ci) {ci.setReturnValue(52);}
    @Inject(method="panelHeight",at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$height(CallbackInfoReturnable<Integer> ci) {ci.setReturnValue(height-12);}
    @Inject(method="visibleRows",at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$rows(CallbackInfoReturnable<Integer> ci) {ci.setReturnValue(CatalogWorkspace.layout(mdpro$screen()).rows());}
    @Inject(method={"mouseClicked","m_6375_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$click(double x,double y,int button,CallbackInfoReturnable<Boolean> ci) {ci.setReturnValue(CatalogWorkspace.click(mdpro$screen(),font,x,y,button));}
    @Inject(method={"mouseScrolled","m_6050_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$wheel(double x,double y,double delta,CallbackInfoReturnable<Boolean> ci) {ci.setReturnValue(CatalogWorkspace.wheel(mdpro$screen(),delta));}
    @Inject(method={"keyPressed","m_7933_"},at=@At("HEAD"),cancellable=true,remap=false)
    private void mdpro$key(int key,int scan,int modifiers,CallbackInfoReturnable<Boolean> ci) {if(CatalogWorkspace.key(mdpro$screen(),key))ci.setReturnValue(true);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        return CatalogWorkspace.drag(mdpro$screen(),y)||super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        return CatalogWorkspace.release(mdpro$screen())||super.mouseReleased(x,y,button);
    }
}
