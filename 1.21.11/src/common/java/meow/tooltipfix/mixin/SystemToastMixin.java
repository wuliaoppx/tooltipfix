package meow.tooltipfix.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.ToastFrame;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Replaces the single background blit of a system toast with a pack-faithful nine slice. */
@Mixin(SystemToast.class)
public class SystemToastMixin {
    @Redirect(
            method = LoaderNames.TOAST_RENDER,
            remap = false,
            at = @At(value = "INVOKE", target = LoaderNames.BLIT_SPRITE_TARGET, remap = false))
    private void tooltipfix$background(GuiGraphics g, RenderPipeline pipeline, Identifier sprite,
                                       int x, int y, int w, int h) {
        ToastFrame.draw(g, pipeline, sprite, x, y, w, h);
    }
}
