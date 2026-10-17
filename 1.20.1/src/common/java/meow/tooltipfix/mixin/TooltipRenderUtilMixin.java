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
 * 把 1.20.1 颜色版的 tooltip 外框换成 1.21.2 的 sprite 版。
 *
 * <p>1.20.1 的 {@code GuiGraphics.renderTooltipInternal} 调的就是这个 6 参重载，所以只需要这一个注入点。
 * 注入点用 {@code remap = false} + 各加载器运行期的字面名（Forge 是 SRG 的 m_280205_，Fabric 是
 * intermediary 的 method_47946）—— 这样连 refmap 都不需要，整个 jar 由构建脚本统一重映射即可。
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
