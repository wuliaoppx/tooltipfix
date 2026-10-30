package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test scaffold: with {@code -Dtoastfix.selftest=true} the toast a server with unverified chat would
 * push is kept on screen, so the layout can be checked without joining such a server.
 */
@Mixin(ToastComponent.class)
public abstract class ToastComponentMixin {
    @Inject(method = LoaderNames.TOAST_COMPONENT_RENDER, remap = false, at = @At("HEAD"))
    private void tooltipfix$selftest(GuiGraphics graphics, CallbackInfo ci) {
        if (!Boolean.getBoolean("toastfix.selftest")) {
            return;
        }
        ToastComponent self = (ToastComponent) (Object) this;
        if (self.getToast(SystemToast.class, SystemToast.SystemToastId.PERIODIC_NOTIFICATION) == null) {
            SystemToast.add(self,
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("ok"),
                    Component.empty());
        }
        if (self.getToast(SystemToast.class, SystemToast.SystemToastId.UNSECURE_SERVER_WARNING) != null) {
            return;
        }
        SystemToast.add(self,
                SystemToast.SystemToastId.UNSECURE_SERVER_WARNING,
                Component.translatable("multiplayer.unsecureserver.toast.title"),
                Component.translatable("multiplayer.unsecureserver.toast"));
    }
}
