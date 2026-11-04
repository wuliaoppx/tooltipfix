package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;

/**
 * 截图脚手架（仅 debug）：{@code -Dtooltipfix.selftest.jade=true} 时把玩家视线压低，保证 Jade 一定有
 * 方块目标、信息框稳定出现在画面上，方便自动截图对比「材质包有没有接管 Jade 的框」。
 *
 * <p>只在带这个 JVM 参数时生效，正常游玩不会触发。
 */
public final class JadeSelftest {
    private JadeSelftest() {
    }

    public static void tick() {
        if (!Boolean.getBoolean("tooltipfix.selftest.jade")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        // 低头看脚下的方块：Jade 的目标射线一定命中，信息框就会画出来。
        mc.player.setXRot(80.0F);
    }
}
