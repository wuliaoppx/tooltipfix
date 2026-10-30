package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

/**
 * 截图脚手架：{@code -Dtooltipfix.selftest.tooltip=true} 时自动打开物品栏，把鼠标停到第一个
 * 「有物品的格子」上，好让 tooltip 稳定出现在画面上，方便对比「本模组外框 / ModernUI 外框」。
 *
 * <p>只在 Forge 侧的 client tick 里调用（for debug only），正式游玩不会触发。
 */
public final class TooltipSelftest {
    private static boolean positioned;
    private static boolean seenWorld;

    private TooltipSelftest() {
    }

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("tooltipfix.selftest.tooltip")) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            seenWorld = false;
            positioned = false;
            return;
        }
        seenWorld = true;
        if (!(mc.screen instanceof InventoryScreen screen)) {
            positioned = false;
            mc.setScreen(new InventoryScreen(mc.player));
            return;
        }
        if (positioned) {
            return;
        }
        Slot target = null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.hasItem() && slot.container == mc.player.getInventory()) {
                target = slot;
                break;
            }
        }
        if (target == null) {
            for (Slot slot : screen.getMenu().slots) {
                if (slot.hasItem()) {
                    target = slot;
                    break;
                }
            }
        }
        if (target == null) {
            return;
        }
        // AbstractContainerScreen 的 leftPos/topPos 是 protected（跨包读不到），物品栏尺寸固定 176x166。
        int guiLeft = (screen.width - 176) / 2;
        int guiTop = (screen.height - 166) / 2;
        double scale = mc.getWindow().getGuiScale();
        double x = (guiLeft + target.x + 8) * scale;
        double y = (guiTop + target.y + 8) * scale;
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), x, y);
        positioned = true;
    }
}
