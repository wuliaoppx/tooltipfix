package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.SlotSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.2 的「材质包可自定义格子悬浮高亮框」移植（Fabric / vanilla 1.21.1 端）。
 *
 * <p>vanilla 1.21.1 里那层固定颜色的格子悬浮框仍然是 {@code AbstractContainerScreen} 的静态方法
 * {@code renderSlotHighlight(GuiGraphics, int, int, int)}（intermediary {@code method_33285}），
 * 由 {@code render} 在画完槽位物品之后调用（javap 确认：{@code render} 里 {@code invokestatic a:(Lfhz;III)V}）。
 * 这里直接在它 HEAD 处截住：材质包提供了 {@code container/slot_highlight_*} 就按材质包画并取消原版填充，
 * 没提供就不取消，原版白框照旧。
 *
 * <p>目标方法是静态的，所以处理器必须是 {@code private static}，参数 = 原方法参数 + CallbackInfo。
 * 方法名走 {@link LoaderNames#RENDER_SLOT_HIGHLIGHT}（intermediary 运行期字面名，remap = false）。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(
            method = LoaderNames.RENDER_SLOT_HIGHLIGHT,
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void tooltipfix$slotHighlightSprites(
            GuiGraphics graphics, int x, int y, int blitOffset, CallbackInfo ci) {
        if (SlotSprites.draw(graphics, x, y, blitOffset)) {
            ci.cancel();
        }
    }
}
