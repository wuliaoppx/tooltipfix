package meow.tooltipfix;

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
}
