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
 * Forge 端专用：注入它自己补丁进来的十参 {@code renderTooltipBackground}。
 *
 * <p>Forge 1.20.1 为了 {@code RenderTooltipEvent.Color} 在 {@code TooltipRenderUtil} 里加了一个
 * 十参重载（多出背景上/下、边框上/下四个颜色），并且把 {@code GuiGraphics.renderTooltipInternal}
 * 改成调用它；原来的六参 {@code m_280205_} 只剩别的调用方在用。所以只注六参会出现
 * 「mixin 明明注入成功、日志里有 Mixing，但游戏里完全没效果」——因为注入的方法根本没被调用。
 *
 * <p>这个补丁方法在运行期保留的是普通 Java 名（不是 SRG），所以字面名就是
 * {@code renderTooltipBackground}。四个颜色参数在贴图版里不再使用：和 1.21.2 一样，
 * 样式完全由材质包提供的贴图决定。
 */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilColorMixin {
    @Inject(method = LoaderNames.RENDER_TOOLTIP_BACKGROUND_COLORED, remap = false,
            at = @At("HEAD"), cancellable = true)
    private static void tooltipfix$renderSpriteBackgroundColored(
            GuiGraphics graphics, int x, int y, int width, int height, int z,
            int backgroundTop, int backgroundBottom, int borderTop, int borderBottom, CallbackInfo ci) {
        if (TooltipSprites.render(graphics, x, y, width, height, z)) {
            ci.cancel();
        }
    }
}
