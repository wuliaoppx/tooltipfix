package meow.tooltipfix.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 1.21.2 起的「材质包可自定义格子悬浮高亮框」搬到 1.20.1。
 *
 * <p>1.20.1 的 {@code AbstractContainerScreen.renderSlotHighlight} 只画一层固定颜色的
 * {@code fillGradient}；1.21.2 改成按 {@code container/slot_highlight_back} 与
 * {@code container/slot_highlight_front} 两个 GUI sprite 画（各自带 {@code gui.scaling} 九宫格描述），
 * 位置是 {@code (slot.x - 4, slot.y - 4, 24, 24)}，back 在物品之前、front 在物品之后。
 *
 * <p>1.20.1 没有 sprite 图集，所以这里直接按材质包里的 PNG + .mcmeta 画（跟 TooltipSprites 一个路子）；
 * 材质包没提供这两个 sprite 时返回 false，调用方回落原版填充色。
 */
public final class SlotSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    /** 1.21.2 原版字段：SLOT_HIGHLIGHT_BACK / SLOT_HIGHLIGHT_FRONT。 */
    private static final ResourceLocation BACK = new ResourceLocation("minecraft", "container/slot_highlight_back");
    private static final ResourceLocation FRONT = new ResourceLocation("minecraft", "container/slot_highlight_front");

    /** 原版是 {@code slot.x - 4}，画 24x24（比 16x16 的格子每边多出 4 像素）。 */
    private static final int OVERHANG = 4;
    private static final int SIZE = 24;

    private static boolean loggedOnce = false;

    /** 诊断用：HEAD 注入到底有没有跑到（被别的 mod 抢先 cancel 时这里不会打印）。 */
    private static boolean hookLogged = false;

    private SlotSprites() {
    }

    /**
     * 由 {@code AbstractContainerScreenMixin} 在 HEAD 注入的一开始调用：用来把「注入没生效/被别的 mod 抢先
     * 取消」和「sprite 没加载到」这两种情况区分开（前者不会打出本行，后者会打 sprite/ACTIVE 行）。
     */
    public static void noteHookFired() {
        if (!hookLogged) {
            hookLogged = true;
            LOGGER.info("[tooltipfix] slot highlight HEAD hook reached");
        }
    }

    /**
     * @return true = 已按材质包画过高亮框；false = 材质包没提供，调用方照原样画原版填充
     */
    public static boolean draw(GuiGraphics graphics, int x, int y) {
        GuiSprite back = GuiSprite.of(BACK);
        GuiSprite front = GuiSprite.of(FRONT);
        // 1.21.2 的顺序是 back 画在物品之前、front 画在物品之后，两者都是「白色半透明蒙版 + 描边」。
        // 1.20.1 的高亮是在 renderSlot（物品已画完）之后才调到这个方法的，没有位置把 back 塞到物品下面，
        // 而 back 是白色半透明填充（原版 alpha 96、CozyUI alpha 64），盖在物品上会把物品冲淡，
        // 所以：有 front（描边框，中间是透明的）就只画 front；只有 back 时才退回画 back。
        GuiSprite chosen = front != null ? front : back;
        if (chosen == null) {
            return false;
        }
        if (!loggedOnce) {
            loggedOnce = true;
            LOGGER.info("[tooltipfix] slot highlight ACTIVE (back={} front={})",
                    packOf(BACK), packOf(FRONT));
        }
        // 1.20.1 的「无颜色」innerBlit 只 setShader + 上传顶点，既不 enableBlend 也不设混合因子
        // （Fabric method_25295 与 Forge m_280444_ 字节码一致；只有带颜色的方法_48466/m_280479_ 才开混合）。
        // 本 mixin 在 HEAD 取消原版填充，混合状态就完全取决于进入前调用点留下的环境：Fabric 实测
        // 描边是未混合的纯黑 (0,0,0)、低 alpha 圆角是纯白，而 Forge 实测是混合后的 (31,31,32)。
        // 这里显式开混合、恢复默认因子，画完再关掉，等价于原版「带颜色」重载的行为。
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        chosen.draw(graphics, x - OVERHANG, y - OVERHANG, SIZE, SIZE);
        RenderSystem.disableBlend();
        return true;
    }

    private static String packOf(ResourceLocation spriteId) {
        GuiSprite sprite = GuiSprite.of(spriteId);
        return sprite == null ? "<missing>" : sprite.packId();
    }
}
