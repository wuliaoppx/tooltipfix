package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/**
 * 自检：循环发两个 system toast，方便截图对比框体。
 *
 * <p>打开方式两种：{@code -Dtooltipfix.selftest=true}，或者
 * {@code config/tooltipfix-toast.txt} 里写上 {@code selftest}（省得改启动参数）。
 */
public final class ToastSelftest {
    private static int counter;

    private ToastSelftest() {
    }

    public static void tick(ToastManager manager) {
        PackReloadSelftest.tick(Minecraft.getInstance());
        if (!ToastFrame.selftest() || ++counter % 160 != 0) {
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
