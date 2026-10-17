package meow.tooltipfix.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 1.21 起的「材质包可动态修改按钮等控件材质」搬到 1.20.1。
 *
 * <p>1.20.1 的按钮还是从 {@code textures/gui/widgets.png} 里按固定 UV + 固定九宫格边距
 * （20/4，200x20）抠图；1.21 改成按状态画 {@code widget/button}、{@code widget/button_disabled}、
 * {@code widget/button_highlighted} 三个 sprite（材质包各自带自己的 {@code gui.scaling} 九宫格描述）。
 *
 * <p>所以这里只拦 {@code AbstractButton.renderWidget} 里那一次 {@code blitNineSliced}：材质包提供了
 * 对应 sprite 就自己画，没提供就原样调回原版。
 */
public final class WidgetSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    private static final ResourceLocation WIDGETS = new ResourceLocation("minecraft", "textures/gui/widgets.png");

    private static final ResourceLocation BUTTON = sprite("widget/button");
    private static final ResourceLocation BUTTON_DISABLED = sprite("widget/button_disabled");
    private static final ResourceLocation BUTTON_HIGHLIGHTED = sprite("widget/button_highlighted");

    /** 原版 AbstractButton.getTextureY() 的三个取值。 */
    private static final int TEXTURE_Y_DISABLED = 46;
    private static final int TEXTURE_Y_NORMAL = 66;
    private static final int TEXTURE_Y_HIGHLIGHTED = 86;

    private static final java.util.Set<String> DIAGNOSED = new java.util.HashSet<>();

    private WidgetSprites() {
    }

    private static ResourceLocation sprite(String path) {
        return new ResourceLocation("minecraft", path);
    }

    /**
     * 原版 {@code GuiGraphics.blitNineSliced} 的替身（只处理按钮那一组 UV）。
     *
     * @return true = 已用材质包的 sprite 画过；false = 材质包没提供，调用方照原样画
     */
    public static boolean drawNineSliced(GuiGraphics graphics, ResourceLocation texture,
                                         int x, int y, int width, int height,
                                         int borderX, int borderY, int uWidth, int uHeight, int u, int v) {
        if (!WIDGETS.equals(texture)) {
            return false;
        }
        ResourceLocation spriteId;
        switch (v) {
            case TEXTURE_Y_DISABLED:
                spriteId = BUTTON_DISABLED;
                break;
            case TEXTURE_Y_NORMAL:
                spriteId = BUTTON;
                break;
            case TEXTURE_Y_HIGHLIGHTED:
                spriteId = BUTTON_HIGHLIGHTED;
                break;
            default:
                return false;
        }

        GuiSprite sprite = GuiSprite.of(spriteId);
        boolean fresh = DIAGNOSED.size() < 256 && DIAGNOSED.add(spriteId.getPath() + "@" + width + "x" + height);
        if (sprite == null) {
            if (fresh) {
                LOGGER.info("[tooltipfix] button {} on {}x{}: no sprite in any pack -> legacy widgets.png",
                        spriteId.getPath(), width, height);
            }
            return false;
        }
        // 1.0.20：用户明确要求保留材质包的悬停动画 ⇒ 不再因为「画布比按钮小 / 内区被拉伸」回落旧图
        // （1.0.18 的 4 倍阈值和 1.0.19 的 canvasTooSmall 都撤掉）。九宫格几何本身与 vanilla 1.21.1
        // 的 blitNineSlicedSprite 一致，这里只把解析出来的几何打进日志，方便核对材质包写法。
        float innerStretch = sprite.innerStretch(width, height);
        if (fresh) {
            LOGGER.info("[tooltipfix] button {} on {}x{}: {} innerStretch={} -> SPRITE",
                    spriteId.getPath(), width, height, sprite.describe(),
                    String.format(java.util.Locale.ROOT, "%.2f", innerStretch));
        }
        // 材质包自己的 gui.scaling 决定九宫格边距，这里不需要原版那套 20/4、200x20
        sprite.draw(graphics, x, y, width, height);
        return true;
    }

}
