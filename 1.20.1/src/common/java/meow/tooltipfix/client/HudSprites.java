package meow.tooltipfix.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 1.20.2 起的「材质包可动态修改物品栏 / 经验栏 / 跳跃条」搬到 1.20.1。
 *
 * <p>1.20.1 的 HUD 还是从 {@code textures/gui/widgets.png} 和 {@code textures/gui/icons.png} 里按
 * 固定 UV 抠图。1.20.2 改成画 {@code gui/sprites/hud/**} 下的 sprite：hotbar、hotbar_selection
 * （8 帧动画的选择框）、experience_bar_background/progress、jump_bar_*、hotbar_attack_indicator_*。
 *
 * <p>做法不是重写整个 {@code Gui.renderHotbar}（那里面还有物品格渲染），而是把原版那几个
 * {@code blit} 调用拦下来：材质包提供了对应 sprite 就自己画，没提供就原样走原版 blit。
 * 判定维度就是原版那组固定 UV（是否 widgets/icons、u、v）。
 */
public final class HudSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    private static final ResourceLocation WIDGETS = new ResourceLocation("minecraft", "textures/gui/widgets.png");
    private static final ResourceLocation ICONS = new ResourceLocation("minecraft", "textures/gui/icons.png");

    private static final ResourceLocation HOTBAR = sprite("hud/hotbar");
    private static final ResourceLocation HOTBAR_SELECTION = sprite("hud/hotbar_selection");
    private static final ResourceLocation HOTBAR_OFFHAND_LEFT = sprite("hud/hotbar_offhand_left");
    private static final ResourceLocation HOTBAR_OFFHAND_RIGHT = sprite("hud/hotbar_offhand_right");
    private static final ResourceLocation ATTACK_BACKGROUND = sprite("hud/hotbar_attack_indicator_background");
    private static final ResourceLocation ATTACK_PROGRESS = sprite("hud/hotbar_attack_indicator_progress");
    private static final ResourceLocation XP_BACKGROUND = sprite("hud/experience_bar_background");
    private static final ResourceLocation XP_PROGRESS = sprite("hud/experience_bar_progress");
    private static final ResourceLocation JUMP_BACKGROUND = sprite("hud/jump_bar_background");
    private static final ResourceLocation JUMP_PROGRESS = sprite("hud/jump_bar_progress");
    private static final ResourceLocation JUMP_COOLDOWN = sprite("hud/jump_bar_cooldown");

    private static boolean loggedOnce = false;

    private HudSprites() {
    }

    private static ResourceLocation sprite(String path) {
        return new ResourceLocation("minecraft", path);
    }

    /**
     * 原版 HUD blit 的替身。
     *
     * @return true 表示已经用材质包的 sprite 画过，调用方不要再画原版；false 表示材质包没提供，
     *         调用方按原参数走原版 blit。
     */
    public static boolean drawOriginalBlit(GuiGraphics graphics, ResourceLocation texture,
                                           int x, int y, int u, int v, int width, int height) {
        if (WIDGETS.equals(texture)) {
            if (u == 0 && v == 0) {
                return draw(graphics, HOTBAR, x, y, 182, 22);
            }
            if (u == 0 && v == 22) {
                return draw(graphics, HOTBAR_SELECTION, x, y, 24, 23);
            }
            if (u == 24 && v == 22) {
                return draw(graphics, HOTBAR_OFFHAND_LEFT, x, y, 29, 24);
            }
            if (u == 53 && v == 22) {
                return draw(graphics, HOTBAR_OFFHAND_RIGHT, x, y, 29, 24);
            }
            return false;
        }
        if (ICONS.equals(texture)) {
            if (u == 0 && v == 64) {
                return draw(graphics, XP_BACKGROUND, x, y, 182, 5);
            }
            if (u == 0 && v == 69) {
                return crop(graphics, XP_PROGRESS, x, y, width, height, 0, 0, 182, 5);
            }
            if (u == 0 && v == 84) {
                return draw(graphics, JUMP_BACKGROUND, x, y, 182, 5);
            }
            if (u == 0 && v == 74) {
                return draw(graphics, JUMP_COOLDOWN, x, y, 182, 5);
            }
            if (u == 0 && v == 89) {
                return crop(graphics, JUMP_PROGRESS, x, y, width, height, 0, 0, 182, 5);
            }
            if (u == 0 && v == 94) {
                return draw(graphics, ATTACK_BACKGROUND, x, y, 18, 18);
            }
            if (u == 18) {
                // 原版是从 (18, 112-高度) 抠一条贴到底部，换成独立 sprite 就是底部对齐裁剪
                return cropBottom(graphics, ATTACK_PROGRESS, x, y, width, height);
            }
            return false;
        }
        return false;
    }

    private static boolean draw(GuiGraphics graphics, ResourceLocation spriteId, int x, int y, int width, int height) {
        GuiSprite sprite = GuiSprite.of(spriteId);
        if (sprite == null) {
            return false;
        }
        logOnce();
        sprite.draw(graphics, x, y, width, height);
        return true;
    }

    private static boolean crop(GuiGraphics graphics, ResourceLocation spriteId,
                                int x, int y, int width, int height, int srcX, int srcY,
                                int srcLogicalWidth, int srcLogicalHeight) {
        GuiSprite sprite = GuiSprite.of(spriteId);
        if (sprite == null) {
            return false;
        }
        logOnce();
        sprite.drawRegion(graphics, x, y, width, height, srcX, srcY, srcLogicalWidth, srcLogicalHeight);
        return true;
    }


    private static boolean cropBottom(GuiGraphics graphics, ResourceLocation spriteId,
                                      int x, int y, int width, int height) {
        GuiSprite sprite = GuiSprite.of(spriteId);
        if (sprite == null) {
            return false;
        }
        logOnce();
        int srcY = Math.max(0, 18 - height);
        sprite.drawRegion(graphics, x, y, width, height, 0, srcY, 18, 18);
        return true;
    }

    /** 只打一次，说明 HUD sprite 有没有生效（以及来自哪个包），方便游戏内快速判断。 */
    private static void logOnce() {
        if (loggedOnce) {
            return;
        }
        loggedOnce = true;
        LOGGER.info("[tooltipfix] HUD sprites ACTIVE (hotbar pack={}, xp bar pack={})",
                packOf(HOTBAR), packOf(XP_BACKGROUND));
    }

    private static String packOf(ResourceLocation spriteId) {
        GuiSprite sprite = GuiSprite.of(spriteId);
        return sprite == null ? "<missing>" : sprite.packId();
    }
}
