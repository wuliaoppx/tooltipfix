package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * 自测脚手架：{@code -Dtooltipfix.selftest=true} 时在世界里钉住两条系统提示——
 * 一条 160 宽单行（走 {@code SystemToast} 的快路径）、一条 230×44 两行（走三段横切/九宫格），
 * 这样不用连服务器就能截到「无法验证聊天消息」的两种高度。
 *
 * <p>触发点是 {@code GuiHudMixin} 的 HUD blit 重定向：那是两个加载器上都有、且每帧都会跑的钩子，
 * 所以这里不需要额外的 tick mixin，也不依赖 fabric-api 的 ClientTickEvents。
 */
public final class ToastSelftest {
    private static boolean fired;

    private ToastSelftest() {
    }

    /** 每帧调用；只在第一次真正进到世界里时加一次 toast，之后每 8 秒续一次。 */
    public static void tick() {
        if (!Boolean.getBoolean("tooltipfix.selftest")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.font == null || mc.getToasts() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (fired && now - last < 8000L) {
            return;
        }
        fired = true;
        last = now;
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal("ok"), null);
        // multiline() 只负责构造，必须自己塞进 ToastComponent。
        mc.getToasts().addToast(SystemToast.multiline(mc, SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING,
                Component.translatable("multiplayer.unsecureserver.toast.title"),
                Component.translatable("multiplayer.unsecureserver.toast")));
    }

    private static long last;
}
