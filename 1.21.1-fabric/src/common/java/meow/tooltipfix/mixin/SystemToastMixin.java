package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.ToastFrame;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixes the system toast background on 1.21.1.
 *
 * <p>Vanilla tiles {@code toast/system} with hard-coded caps (20 / 64 / 60) and a hard-coded base
 * size of 160x32. That only lines up with the vanilla sprite; a pack that ships a bigger sprite, or
 * one that carries {@code gui.scaling} nine_slice metadata (which 1.21.1 never reads here), comes out
 * misaligned or seamed. When the sprite does declare nine_slice we draw the frame in one piece and
 * swallow vanilla's per-row calls.
 *
 * <p>方法名取自 {@link LoaderNames}（{@code remap = false}）：build.ps1 会把 jar 重映射到各加载器的
 * 命名空间，但注解里的字符串不会被重映射。
 */
@Mixin(SystemToast.class)
public abstract class SystemToastMixin {
    private static final ResourceLocation TOASTFIX$SPRITE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "toast/system");
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-toast");

    @Inject(method = LoaderNames.TOAST_RENDER_BACKGROUND_ROW, remap = false,
            at = @At("HEAD"), cancellable = true)
    private void tooltipfix$frame(GuiGraphics graphics, int width, int vOff, int y, int h, CallbackInfo ci) {
        boolean has = ToastFrame.has(TOASTFIX$SPRITE);
        LOG.info("row width={} vOff={} y={} h={} has={} height={}", width, vOff, y, h, has,
                ((SystemToast) (Object) this).height());
        if (!has) {
            return;
        }
        // Every row is replaced by a single nine-slice pass, drawn on the first (top) row.
        ci.cancel();
        if (vOff == 0) {
            ToastFrame.draw(graphics, TOASTFIX$SPRITE, 0, 0, width, ((SystemToast) (Object) this).height());
        }
    }

    @Redirect(method = LoaderNames.TOAST_RENDER, remap = false,
            at = @At(value = "INVOKE", target = LoaderNames.GUI_GRAPHICS_BLIT_SPRITE, remap = false))
    private void tooltipfix$flat(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int w, int h) {
        ToastFrame.draw(graphics, sprite, x, y, w, h);
    }
}
