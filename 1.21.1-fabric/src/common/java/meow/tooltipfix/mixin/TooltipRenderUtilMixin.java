package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
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
 * <p>Only the six argument overload exists in vanilla, and that is the one
 * {@code GuiGraphics.renderTooltipInternal} calls. If the sprites are not on the GUI atlas the
 * injection does not cancel, so the stock colour rendering still runs.
 */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilMixin {
    @Inject(method = LoaderNames.RENDER_TOOLTIP_BACKGROUND, remap = false, at = @At("HEAD"), cancellable = true)
    private static void tooltipfix$renderSpriteBackground(
            GuiGraphics graphics, int x, int y, int width, int height, int z, CallbackInfo ci) {
        if (TooltipSprites.render(graphics, x, y, width, height, z)) {
            ci.cancel();
        }
    }
}
