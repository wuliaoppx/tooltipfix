package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.ToastSelftest;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the optional self test from the toast manager tick. */
@Mixin(ToastManager.class)
public class ToastSelftestMixin {
    @Inject(method = LoaderNames.TOAST_MANAGER_UPDATE, remap = false, at = @At("TAIL"))
    private void tooltipfix$tick(CallbackInfo ci) {
        ToastSelftest.tick((ToastManager) (Object) this);
    }
}
