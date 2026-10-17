package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.EffectSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.20.2 的「材质包可改药水效果背景」移植（物品栏右侧效果列表那一半）。
 *
 * <p>1.20.1 的 {@code EffectRenderingInventoryScreen.renderBackgrounds} 直接
 * {@code blit(textures/gui/container/inventory.png, x, y, 0, 166, 120, 32)}（带名字）和
 * {@code (…, 0, 198, 32, 32)}（只有图标）；1.20.2 起改为画
 * {@code container/inventory/effect_background_large} / {@code _small} 两个 sprite。
 *
 * <p>这里把那个 7 参 {@code blit} 重定向掉：材质包提供了 sprite 就按原参数尺寸画 sprite（含 animation
 * 动态贴图），没提供就走原版 blit，所以没装材质包时行为与 1.20.1 原版完全一致。
 */
@Mixin(EffectRenderingInventoryScreen.class)
public abstract class EffectRenderingInventoryScreenMixin {
    @Redirect(
            method = LoaderNames.EFFECT_SCREEN_RENDER_BACKGROUNDS,
            remap = false,
            require = 0,
            at = @At(value = "INVOKE", target = LoaderNames.GUI_GRAPHICS_BLIT_7, remap = false))
    private void tooltipfix$effectListBackground(GuiGraphics graphics, ResourceLocation texture,
                                                 int x, int y, int u, int v, int width, int height) {
        if (!EffectSprites.drawOriginalBlit(graphics, texture, x, y, u, v, width, height)) {
            graphics.blit(texture, x, y, u, v, width, height);
        }
    }
}
