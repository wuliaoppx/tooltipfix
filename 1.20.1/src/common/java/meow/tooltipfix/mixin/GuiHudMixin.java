package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.EffectSprites;
import meow.tooltipfix.client.HudSprites;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 1.20.2 起的 HUD sprite（物品栏 / 选择框 / 经验栏）接到 1.20.1 的 HUD 上。
 *
 * <p>没有重写 {@code Gui.renderHotbar}（里面还有物品格、副手、攻击指示器的渲染），而是把这三个方法里
 * 画 GUI 贴图的 {@code GuiGraphics.blit(ResourceLocation, int x, int y, int u, int v, int w, int h)}
 * 调用重定向掉：材质包提供了对应 sprite 就自己按 sprite 画，没提供就原样调回原版 blit —— 所以
 * 没装材质包时行为与 1.20.1 原版完全一致。
 *
 * <p>{@code require = 0}：不同加载器/版本里这些方法名不同（见 LoaderNames），找不到就不注入、不报错。
 */
@Mixin(Gui.class)
public abstract class GuiHudMixin {
    @Redirect(
            method = {
                    LoaderNames.GUI_RENDER_HOTBAR,
                    LoaderNames.GUI_RENDER_EXPERIENCE_BAR
            },
            remap = false,
            require = 0,
            at = @At(value = "INVOKE", target = LoaderNames.GUI_GRAPHICS_BLIT_7, remap = false))
    private void tooltipfix$hudBlit(GuiGraphics graphics, ResourceLocation texture,
                                    int x, int y, int u, int v, int width, int height) {
        if (!HudSprites.drawOriginalBlit(graphics, texture, x, y, u, v, width, height)) {
            graphics.blit(texture, x, y, u, v, width, height);
        }
    }

    /**
     * 药水效果框背景（1.20.2 起改为画 {@code hud/effect_background} / {@code hud/effect_background_ambient}）。
     *
     * <p>原版 {@code Gui.renderEffects} 里只有两处 24x24 的 {@code inventory.png} blit（UV 141,166 普通、
     * 165,166 环境）；@Redirect 会把两处都拦下来，由 {@link EffectSprites} 按 UV 区分并画对应 sprite。
     */
    @Redirect(
            method = LoaderNames.GUI_RENDER_EFFECTS,
            remap = false,
            require = 0,
            at = @At(value = "INVOKE", target = LoaderNames.GUI_GRAPHICS_BLIT_7, remap = false))
    private void tooltipfix$effectBackgroundBlit(GuiGraphics graphics, ResourceLocation texture,
                                                 int x, int y, int u, int v, int width, int height) {
        if (!EffectSprites.drawOriginalBlit(graphics, texture, x, y, u, v, width, height)) {
            graphics.blit(texture, x, y, u, v, width, height);
        }
    }
}
