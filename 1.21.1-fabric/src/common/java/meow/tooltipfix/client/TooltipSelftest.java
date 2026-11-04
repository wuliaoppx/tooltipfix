package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 截图脚手架：{@code -Dtooltipfix.selftest.tooltip=true} 时自动打开物品栏，把鼠标停到第一个
 * 「有物品的格子」上，好让 tooltip 稳定出现在画面上，方便对比「本模组外框 / ModernUI 外框」。
 *
 * <p>由 {@code GuiMixin} 每 tick 调一次（debug only，正式游玩不会触发）。全程在游戏进程内操作，
 * 不需要窗口获得系统焦点，所以无人值守也能出图。
 */
public final class TooltipSelftest {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-selftest");
    private static String lastState;
    private static boolean positioned;
    private static int calls;

    private TooltipSelftest() {
    }

    public static void tick(Minecraft mc) {
        JadeSelftest.tick(mc);
        if (!Boolean.getBoolean("tooltipfix.selftest.tooltip")) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            positioned = false;
            return;
        }
        String state = (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
        if (calls < 6) {
            calls++;
            LOG.info("selftest: call {} screen={} positioned={}", calls, state, positioned);
        }
        if (!state.equals(lastState)) {
            lastState = state;
            LOG.info("selftest: screen={}", state);
        }
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            positioned = false;
            mc.setScreen(new InventoryScreen(mc.player));
            return;
        }
        if (positioned) {
            return;
        }
        Slot target = null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.hasItem()) {
                target = slot;
                break;
            }
        }
        if (target == null) {
            // 创造模式背包可能是空的：客户端塞一个石头，保证悬停时有 tooltip 可截（只影响这一局的内存）
            mc.player.getInventory().setItem(0, new ItemStack(Items.STONE, 1));
            return;
        }
        // AbstractContainerScreen 的 leftPos/topPos 是 protected（跨包读不到），物品栏尺寸固定 176x166。
        int guiLeft = (screen.width - 176) / 2;
        int guiTop = (screen.height - 166) / 2;
        try {
            java.lang.reflect.Field lf = AbstractContainerScreen.class.getDeclaredField("leftPos");
            lf.setAccessible(true);
            guiLeft = lf.getInt(screen);
            java.lang.reflect.Field tf = AbstractContainerScreen.class.getDeclaredField("topPos");
            tf.setAccessible(true);
            guiTop = tf.getInt(screen);
        } catch (Throwable ignored) {
        }
        double scale = mc.getWindow().getGuiScale();
        double x = (guiLeft + target.x + 8) * scale;
        double y = (guiTop + target.y + 8) * scale;
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), x, y);
        LOG.info("selftest: hover slot={},{} cursor={},{}", target.x, target.y, x, y);
        positioned = true;
    }
}
