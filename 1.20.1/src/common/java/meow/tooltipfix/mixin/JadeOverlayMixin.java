package meow.tooltipfix.mixin;

import meow.tooltipfix.client.JadeBoxSprites;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import snownee.jade.overlay.TooltipRenderer;

/**
 * 把 Jade 顶部信息框的「颜色框」换成材质包的 GUI sprite（Jade 1.20.1：11.13.x）。
 *
 * <p>注入口选在 {@code OverlayRenderer#drawTooltipBox}：它是 Jade 1.20.1 唯一画外框的地方，
 * 而且只在「没有别的 mod 取消默认渲染 + alpha &gt; 0」时才会被调用，所以 HEAD 取消等价于
 * 原版这一次绘制被我们替换掉。
 *
 * <p>{@link Pseudo}：没装 Jade 时这个 mixin 直接跳过（绝不因为缺目标类而崩）；
 * {@code require = 0}：Jade 版本不同、方法签名变了也只是静默不生效，不会硬崩。
 *
 * <p>注意：{@code method} 只写方法名、不带描述符。写成
 * {@code "drawTooltipBox(Lnet/minecraft/class_332;IIIIFLsnownee/jade/overlay/TooltipRenderer;)V"}
 * 时 Mixin 认不出来（实测运行期静默不注入），Jade 11.x 里这个名字只有一个重载，用名字即可。
 */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.OverlayRenderer", remap = false)
public abstract class JadeOverlayMixin {
    @Inject(method = "drawTooltipBox", remap = false, at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void tooltipfix$drawJadeBox(
            GuiGraphics graphics, int x, int y, int width, int height, float alpha, boolean square,
            TooltipRenderer tooltip, CallbackInfo ci) {
        JadeBoxSprites.noteHookFired();
        if (JadeBoxSprites.draw(graphics, x, y, width, height, alpha)) {
            ci.cancel();
        }
    }
}
