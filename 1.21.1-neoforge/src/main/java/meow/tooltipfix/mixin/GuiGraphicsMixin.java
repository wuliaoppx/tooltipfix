package meow.tooltipfix.mixin;

import meow.tooltipfix.client.GuiSpriteStretching;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores the 1.21.2 {@code stretch_inner} behaviour of {@code GuiGraphics.blitNineSlicedSprite}.
 *
 * <p>Vanilla 1.21.1 loops {@code blitTiledSprite} over the inner rows/columns/centre. When a pack
 * asks for {@code stretch_inner} we take over and draw the same nine regions with the centre quad
 * stretched, which is what 1.21.2 does. Sprites that do not opt in keep the stock path untouched.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Inject(method = "blitNineSlicedSprite", at = @At("HEAD"), cancellable = true)
    private void tooltipfix$stretchInner(
            TextureAtlasSprite sprite,
            GuiSpriteScaling.NineSlice nineSlice,
            int x, int y, int z, int width, int height,
            CallbackInfo ci) {
        if (GuiSpriteStretching.stretchInner(sprite)) {
            GuiSpriteStretching.blitNineSliceStretched(
                    (GuiGraphics) (Object) this, sprite, nineSlice, x, y, z, width, height);
            ci.cancel();
        }
    }
}
