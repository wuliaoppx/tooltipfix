package meow.tooltipfix.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import meow.tooltipfix.client.ToastFrame;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.x draws the system toast background with a single {@code blitSprite} call and lets the
 * sprite's {@code gui.scaling} metadata do the nine-slice work. That path clamps every border to
 * half of the target size, so a tall border is squashed on short toasts.
 */
@Mixin(SystemToast.class)
public abstract class SystemToastMixin {
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
            ),
            remap = false
    )
    private void tooltipfix$frame(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite,
                                  int x, int y, int w, int h) {
        ToastFrame.draw(g, pipeline, sprite, x, y, w, h);
    }
}
