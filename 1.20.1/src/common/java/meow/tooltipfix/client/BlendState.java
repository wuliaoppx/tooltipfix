package meow.tooltipfix.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 需要「显式摆好混合状态、画完再还原」时用的小工具（1.20.1 端）。
 *
 * <p>1.20.1 里凡是接管原版绘制后自己落笔的地方，都要注意：无颜色的 {@code innerBlit}
 * （Fabric {@code method_25295} / Forge {@code m_280444_}）既不 {@code enableBlend()} 也不设混合因子，
 * sprite 是否按 alpha 混合完全取决于调用点遗留的状态——1.20.1 Fabric 的高亮框描边变纯黑就是这个坑。
 *
 * <p>这里先把混合打开并设成原版因子（画得对），画完再按进来前的真实状态还原（不扰动后面的绘制）。
 * 状态用 LWJGL 直接查真实 GL 开关：只查不设，不会与 {@code GlStateManager} 的缓存失同步。
 */
public final class BlendState {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");
    private static boolean logged = false;

    private BlendState() {
    }

    /** @return 进来前混合是否开着，原样交给 {@link #end(boolean)} 还原。 */
    public static boolean begin(String site) {
        boolean wasEnabled = true;
        try {
            wasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        } catch (Throwable ignored) {
            // 查不到就当它开着：宁可什么也不还原，也不要把调用点的状态从「开」改成「关」
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[tooltipfix] {} inherited GL_BLEND={} -> forcing enabled + default factors",
                    site, wasEnabled);
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        return wasEnabled;
    }

    public static void end(boolean wasEnabled) {
        if (!wasEnabled) {
            RenderSystem.disableBlend();
        }
    }
}
