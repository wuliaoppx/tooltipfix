package meow.tooltipfix.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 1.20.2 起的「材质包可改药水效果背景」搬到 1.20.1。
 *
 * <p>1.20.1 的药水效果背景还是从 {@code textures/gui/container/inventory.png} 里按固定 UV 抠图，两处：
 * <ul>
 *   <li>HUD 药水框：{@code Gui.renderEffects} 里 24x24，UV (141,166) 普通 / (165,166) 环境（{@code isAmbient()}）；</li>
 *   <li>物品栏右侧的效果列表：{@code EffectRenderingInventoryScreen.renderBackgrounds} 里 120x32（带名字）与
 *       32x32（只有图标），UV (0,166) 与 (0,198)。</li>
 * </ul>
 * 1.20.2 起这两处都改成画 GUI sprite：{@code hud/effect_background(_ambient)} 与
 * {@code container/inventory/effect_background_large/_small}（与 1.21.1 原版常量同名）。
 *
 * <p>做法与 HUD 其它 sprite 一致：拦住原版那次 {@code GuiGraphics.blit}，材质包提供了对应 sprite 就自己画，
 * 没提供就原样走原版。位置和尺寸完全沿用原参数，所以几何与 1.20.1 原版一模一样。CozyUI 的
 * {@code effect_background_large} 是 60 帧动态贴图，动画由 {@link GuiSprite} 自己按时间步进。
 */
public final class EffectSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    /** 1.20.1 的效果背景全都来自这张老纹理（HUD 与物品栏效果列表共用）。 */
    private static final ResourceLocation INVENTORY =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");

    private static final ResourceLocation HUD_BACKGROUND = sprite("hud/effect_background");
    private static final ResourceLocation HUD_BACKGROUND_AMBIENT = sprite("hud/effect_background_ambient");
    private static final ResourceLocation LIST_BACKGROUND_LARGE = sprite("container/inventory/effect_background_large");
    private static final ResourceLocation LIST_BACKGROUND_SMALL = sprite("container/inventory/effect_background_small");

    private static boolean loggedOnce = false;

    private EffectSprites() {
    }

    private static ResourceLocation sprite(String path) {
        return new ResourceLocation("minecraft", path);
    }

    /**
     * 原版「从 inventory.png 抠药水效果背景」的替身。
     *
     * @return true 表示已经用材质包的 sprite 画过，调用方不要再画原版；false 表示这次 blit 不是我们管的那几块，
     *         或者材质包没提供，调用方按原参数走原版 blit。
     */
    public static boolean drawOriginalBlit(GuiGraphics graphics, ResourceLocation texture,
                                           int x, int y, int u, int v, int width, int height) {
        if (!INVENTORY.equals(texture)) {
            return false;
        }
        ResourceLocation spriteId;
        if (u == 165 && v == 166 && width == 24 && height == 24) {
            spriteId = HUD_BACKGROUND_AMBIENT;      // 原版 isAmbient() 分支
        } else if (u == 141 && v == 166 && width == 24 && height == 24) {
            spriteId = HUD_BACKGROUND;
        } else if (u == 0 && v == 166 && width == 120 && height == 32) {
            spriteId = LIST_BACKGROUND_LARGE;
        } else if (u == 0 && v == 198 && width == 32 && height == 32) {
            spriteId = LIST_BACKGROUND_SMALL;
        } else {
            return false;
        }
        GuiSprite sprite = GuiSprite.of(spriteId);
        if (sprite == null) {
            return false;
        }
        logOnce();
        // 这两处走的是「无颜色」innerBlit 路径，1.20.1 里它不碰混合开关，得自己摆好再还原。
        boolean blendWasEnabled = BlendState.begin("effect background");
        sprite.draw(graphics, x, y, width, height);
        BlendState.end(blendWasEnabled);
        return true;
    }

    private static void logOnce() {
        if (loggedOnce) {
            return;
        }
        loggedOnce = true;
        LOGGER.info("[tooltipfix] effect background sprites ACTIVE (hud pack={}, list pack={})",
                packOf(HUD_BACKGROUND), packOf(LIST_BACKGROUND_LARGE));
    }

    private static String packOf(ResourceLocation spriteId) {
        GuiSprite sprite = GuiSprite.of(spriteId);
        return sprite == null ? "<missing>" : sprite.packId();
    }
}
