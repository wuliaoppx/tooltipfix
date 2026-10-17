package meow.tooltipfix.mixin;

import meow.tooltipfix.client.TooltipSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the 1.21.1 colour based tooltip box for the 1.21.2 sprite based one.
 *
 * <p>Both overloads are hooked: the six argument one is the public entry point, while
 * {@code GuiGraphics.renderTooltipInternal} goes through the ten argument NeoForge variant that
 * carries {@code RenderTooltipEvent.Color}'s four colours. As in 1.21.2 the colours no longer apply
 * once the box comes from a sprite - the pack's texture decides how it looks. If the sprites are
 * missing, neither injection cancels and the stock colour rendering still runs.
 */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilMixin {
    @Inject(
            method = "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIII)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void tooltipfix$renderSpriteBackground(
            GuiGraphics graphics, int x, int y, int width, int height, int z, CallbackInfo ci) {
        if (TooltipSprites.render(graphics, x, y, width, height, z)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIII)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void tooltipfix$renderSpriteBackgroundColored(
            GuiGraphics graphics, int x, int y, int width, int height, int z,
            int backgroundTop, int backgroundBottom, int borderTop, int borderBottom, CallbackInfo ci) {
        if (TooltipSprites.render(graphics, x, y, width, height, z)) {
            ci.cancel();
        }
    }
}
