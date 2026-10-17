package meow.tooltipfix.mixin;

import meow.tooltipfix.client.SlotSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.2 的「材质包可自定义格子悬浮高亮框」移植（NeoForge 1.21.1 端）。
 *
 * <p>NeoForge 把原版的静态 {@code renderSlotHighlight(GuiGraphics,int,int,int)} 拆成了三层：
 * {@code render} 调 {@code renderSlotHighlight(GuiGraphics, Slot, int, int, float)}，
 * 后者再调 {@code renderSlotHighlight(GuiGraphics, int x, int y, int z, int color)}（static，多了一个
 * slot 颜色参数）。真正落笔的就是这个 static 五参重载，所以在它 HEAD 处截住即可。
 *
 * <p>NeoForge 运行期用的是 Mojang 官方名，所以方法名直接写官方签名（remap = false，不需要 refmap）。
 * 材质包没提供 {@code container/slot_highlight_*} sprite 时不取消，原版填充照跑。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(
            method = "renderSlotHighlight(Lnet/minecraft/client/gui/GuiGraphics;IIII)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void tooltipfix$slotHighlightSprites(
            GuiGraphics graphics, int x, int y, int z, int color, CallbackInfo ci) {
        if (SlotSprites.draw(graphics, x, y, z)) {
            ci.cancel();
        }
    }
}
