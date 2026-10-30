package meow.tooltipfix;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge 侧入口（Fabric 侧是纯 mixin mod，不需要入口点）。 */
@Mod("tooltipfix")
public class TooltipFixForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    public TooltipFixForge() {
        // 只要这行出现在日志里，就说明 Forge 端模组本体加载成功（mixin 是另一条独立通路）。
        LOGGER.info("[tooltipfix] Forge entrypoint constructed");
    }

    /**
     * 自测脚手架：{@code -Dtooltipfix.selftest=true} 时把两种系统提示钉在屏幕上，
     * 不用连服务器也能截到「无法验证聊天消息」的两种高度。
     */
    @Mod.EventBusSubscriber(modid = "tooltipfix", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Client {
        private static int ticks;

        private Client() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                // 截图脚手架：-Dtooltipfix.selftest.tooltip=true 时自动打开物品栏并悬停到有物品的格子。
                net.minecraft.client.Minecraft mcSelf = net.minecraft.client.Minecraft.getInstance();
                if (mcSelf != null) {
                    meow.tooltipfix.client.TooltipSelftest.tick(mcSelf);
                }
            }
            if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("tooltipfix.selftest")) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.getToasts() == null || mc.font == null) {
                return;
            }
            // Re-arm every ~8 s so a screenshot taken at any time still sees both toasts.
            if (++ticks % 160 != 0) {
                return;
            }
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.literal("ok"), null);
            // multiline() only builds the toast; it has to be pushed into the component explicitly.
            mc.getToasts().addToast(SystemToast.multiline(mc, SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING,
                    Component.translatable("multiplayer.unsecureserver.toast.title"),
                    Component.translatable("multiplayer.unsecureserver.toast")));
            LOGGER.info("[tooltipfix] selftest toasts: unsecure={} periodic={}",
                    mc.getToasts().getToast(SystemToast.class, SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING) != null,
                    mc.getToasts().getToast(SystemToast.class, SystemToast.SystemToastIds.PERIODIC_NOTIFICATION) != null);
        }
    }
}
