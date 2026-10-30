package meow.tooltipfix.mixin;

import meow.tooltipfix.client.ToastSelftest;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only used by the self test flag; drives the toast manager from its per-frame update. */
@Mixin(ToastManager.class)
public abstract class ToastSelftestMixin {
    @Inject(method = "update()V", at = @At("HEAD"), remap = false)
    private void tooltipfix$selftest(CallbackInfo ci) {
        ToastSelftest.tick((ToastManager) (Object) this);
    }
}
