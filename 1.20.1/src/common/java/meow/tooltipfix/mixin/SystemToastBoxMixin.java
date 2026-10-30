package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.ToastBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.1：把 {@code SystemToast} 的三段横切背景换成一次性九宫格。
 *
 * <p>方法名写字面运行期名（{@code remap = false}）：build.ps1 会把整个 jar 从 named 重映射到
 * 目标命名空间，而注解里的字符串不会被重映射，所以 Forge 变体编 SRG 名、Fabric 变体编
 * intermediary 名，实际取值见各自的 {@code LoaderNames}。
 */
@Mixin(SystemToast.class)
public abstract class SystemToastBoxMixin {
    /** {@code renderBackgroundRow(GuiGraphics, ToastComponent, int width, int vOff, int y, int h)} */
    @Inject(method = LoaderNames.TOAST_RENDER_BACKGROUND_ROW, remap = false, at = @At("HEAD"), cancellable = true)
    private void tooltipfix$backgroundRows(GuiGraphics graphics, ToastComponent component,
                                           int width, int vOff, int y, int h, CallbackInfo ci) {
        if (!ToastBox.active()) {
            return;
        }
        ci.cancel();
        if (vOff == 0) {
            ToastBox.draw(graphics, width, ((SystemToast) (Object) this).height());
        }
    }

    /** 单行且很窄的时候原版走 {@code blit(TEXTURE, 0, 0, 0, 64, width, height)} 的快路径。 */
    @Redirect(method = LoaderNames.TOAST_RENDER, remap = false,
            at = @At(value = "INVOKE", target = LoaderNames.GUI_GRAPHICS_BLIT_7_TARGET, remap = false))
    private void tooltipfix$fastPath(GuiGraphics graphics, ResourceLocation texture,
                                     int x, int y, int u, int v, int w, int h) {
        if (!ToastBox.active()) {
            graphics.blit(texture, x, y, u, v, w, h);
            return;
        }
        ToastBox.draw(graphics, w, ((SystemToast) (Object) this).height());
    }
}
