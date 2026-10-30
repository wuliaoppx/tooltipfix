package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/**
 * {@code -Dtooltipfix.selftest=true}: keep re-adding the "chat messages can't be verified" toast
 * so the frame can be captured without joining an unverified server.
 */
public final class ToastSelftest {
    private static final boolean ENABLED = Boolean.getBoolean("tooltipfix.selftest");
    private static int counter;

    private ToastSelftest() {
    }

    public static void tick(ToastManager manager) {
        if (!ENABLED || ++counter % 160 != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        manager.addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("ok"), Component.literal("one line")));
        manager.addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.UNSECURE_SERVER_WARNING,
                Component.translatable("multiplayer.unsecureserver.toast.title"),
                Component.translatable("multiplayer.unsecureserver.toast")));
    }
}
