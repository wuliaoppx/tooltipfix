package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.WidgetSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.21 的按钮 sprite 化：1.20.1 的 {@code AbstractButton.renderWidget} 用
 * {@code blitNineSliced(widgets.png, ..., 20, 4, 200, 20, 0, yTex)} 画底板，这里把那一次调用
 * 重定向成「画材质包的 {@code widget/button*} sprite」，材质包没提供时原样回落到原版调用。
 *
 * <p>只重定向底板，按钮文字/图标仍走原版；禁用/普通/高亮三态由原版的 yTex（46/66/86）区分。
 */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {
    @Redirect(
            method = LoaderNames.ABSTRACT_BUTTON_RENDER_WIDGET,
            remap = false,
            require = 0,
            at = @At(value = "INVOKE", target = LoaderNames.GUI_GRAPHICS_BLIT_NINE_SLICED_11, remap = false))
    private void tooltipfix$buttonBackground(GuiGraphics graphics, ResourceLocation texture,
                                             int x, int y, int width, int height,
                                             int borderX, int borderY, int uWidth, int uHeight, int u, int v) {
        if (!WidgetSprites.drawNineSliced(graphics, texture, x, y, width, height,
                borderX, borderY, uWidth, uHeight, u, v)) {
            graphics.blitNineSliced(texture, x, y, width, height, borderX, borderY, uWidth, uHeight, u, v);
        }
    }
}
