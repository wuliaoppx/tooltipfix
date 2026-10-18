package meow.tooltipfix.client;

import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HUD：快捷栏底、选中框、副手框、经验条、跳跃条、攻击指示器。
 *
 * <p>1.12.2 的这些元素全部来自两张老图集的固定 UV：{@code textures/gui/widgets.png}（快捷栏那几块）
 * 和 {@code textures/gui/icons.png}（经验条/跳跃条/攻击指示器）。1.20 起它们改成了独立 sprite，
 * 名字与语义一一对应，所以按 (u, v, w, h) 精确匹配就能对上，不需要知道当前绑的是哪张贴图
 * （两张表的 UV 互不重叠：182x22 只有快捷栏、18x18@v94 只有攻击指示器）。
 *
 * <p>1.12.2 独有的两处差异：
 * <ul>
 *   <li>没有「跳跃冷却条」（1.21 的 v=74），跳过。</li>
 *   <li>攻击指示器的进度是从 18x18 区域的**底部**往上增长的，源矩形是 {@code (18, 112-h, 18, h)}，
 *       所以用 {@code drawRegion} 从 18x18 画布底部裁一条。</li>
 * </ul>
 */
public final class HudSprites {

    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");

    private static final ResourceLocation HOTBAR = new ResourceLocation("minecraft", "hud/hotbar");
    private static final ResourceLocation HOTBAR_SELECTION =
            new ResourceLocation("minecraft", "hud/hotbar_selection");
    private static final ResourceLocation OFFHAND_LEFT =
            new ResourceLocation("minecraft", "hud/hotbar_offhand_left");
    private static final ResourceLocation OFFHAND_RIGHT =
            new ResourceLocation("minecraft", "hud/hotbar_offhand_right");
    private static final ResourceLocation XP_BACKGROUND =
            new ResourceLocation("minecraft", "hud/experience_bar_background");
    private static final ResourceLocation XP_PROGRESS =
            new ResourceLocation("minecraft", "hud/experience_bar_progress");
    private static final ResourceLocation JUMP_BACKGROUND =
            new ResourceLocation("minecraft", "hud/jump_bar_background");
    private static final ResourceLocation JUMP_PROGRESS =
            new ResourceLocation("minecraft", "hud/jump_bar_progress");
    private static final ResourceLocation ATTACK_BACKGROUND =
            new ResourceLocation("minecraft", "hud/hotbar_attack_indicator_background");
    private static final ResourceLocation ATTACK_PROGRESS =
            new ResourceLocation("minecraft", "hud/hotbar_attack_indicator_progress");
    /**
     * 准星侧的攻击指示器（1.12.2 画在准星正下方 16x4 的那一条，以及蓄力满时的 16x16 图标）。
     *
     * <p>这三个 UV 之前一直走透传，而材质包已经把 icons.png 按 1.20.1 的布局重排过了，于是
     * 原版 UV 在那张图上只剩零星的杂点——用户看到的「准星右下角有一个像素」就是它。
     */
    private static final ResourceLocation CROSSHAIR_ATTACK_BACKGROUND =
            new ResourceLocation("minecraft", "hud/crosshair_attack_indicator_background");
    private static final ResourceLocation CROSSHAIR_ATTACK_PROGRESS =
            new ResourceLocation("minecraft", "hud/crosshair_attack_indicator_progress");
    private static final ResourceLocation CROSSHAIR_ATTACK_FULL =
            new ResourceLocation("minecraft", "hud/crosshair_attack_indicator_full");
    /**
     * 准星本体。1.12.2 从 {@code icons.png} 的 (0,0,16,16) 抠，而材质包那张 icons.png 是按 1.20.1
     * 布局放的，同一格里除了十字还压着一个青绿小点（tex 32..35 / 60..63），反色混合后就是用户
     * 报的「准星右下角有一个像素」。1.21.2 的 {@code hud/crosshair} 是干净的 15x15。
     */
    private static final ResourceLocation CROSSHAIR =
            new ResourceLocation("minecraft", "hud/crosshair");

    /** 经验条/跳跃条画布（1.12.2 与 1.20 一致）。 */
    private static final int BAR_CANVAS_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int ATTACK_CANVAS = 18;
    /** icons.png 里攻击指示器区域的顶边：源 v = 112 - h。 */
    private static final int ATTACK_REGION_BOTTOM = 112;

    private static GuiSprite hotbar;
    private static GuiSprite hotbarSelection;
    private static GuiSprite offhandLeft;
    private static GuiSprite offhandRight;
    private static GuiSprite xpBackground;
    private static GuiSprite xpProgress;
    private static GuiSprite jumpBackground;
    private static GuiSprite jumpProgress;
    private static GuiSprite attackBackground;
    private static GuiSprite attackProgress;
    private static GuiSprite crosshairAttackBackground;
    private static GuiSprite crosshairAttackProgress;
    private static GuiSprite crosshairAttackFull;
    private static GuiSprite crosshair;
    private static boolean loggedOnce;

    private HudSprites() {
    }

    public static boolean blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (u == 0 && v == 0 && width == 16 && height == 16) {
            return drawCrosshair(zLevel, x, y);
        }
        if (u == 0 && v == 0 && width == 182 && height == 22) {
            return draw(hotbar(), zLevel, x, y, 182, 22);
        }
        if (u == 0 && v == 22 && width == 24 && height == 22) {
            return draw(hotbarSelection(), zLevel, x, y, 24, 22);
        }
        if (u == 24 && v == 22 && width == 29 && height == 24) {
            return draw(offhandLeft(), zLevel, x, y, 29, 24);
        }
        if (u == 53 && v == 22 && width == 29 && height == 24) {
            return draw(offhandRight(), zLevel, x, y, 29, 24);
        }
        if (u == 0 && v == 64 && width == 182 && height == 5) {
            return draw(xpBackground(), zLevel, x, y, 182, 5);
        }
        if (u == 0 && v == 84 && width == 182 && height == 5) {
            return draw(jumpBackground(), zLevel, x, y, 182, 5);
        }
        if (u == 0 && v == 94 && width == 18 && height == 18) {
            return draw(attackBackground(), zLevel, x, y, 18, 18);
        }
        if (u == 0 && v == 69 && height == 5 && width > 0 && width <= BAR_CANVAS_WIDTH) {
            return drawBar(xpProgress(), zLevel, x, y, width);
        }
        if (u == 0 && v == 89 && height == 5 && width > 0 && width <= BAR_CANVAS_WIDTH) {
            return drawBar(jumpProgress(), zLevel, x, y, width);
        }
        if (u == 18 && width == 18 && height > 0 && height <= ATTACK_CANVAS
                && v + height == ATTACK_REGION_BOTTOM) {
            return drawAttackProgress(x, y, height, zLevel);
        }
        // 准星侧攻击指示器（GuiIngame.renderCrosshair）：满蓄力 (68,94,16,16)、
        // 空条背景 (36,94,16,4)、进度 (52,94,n,4)。三段都在原版的「反色混合」块里
        // （ONE_MINUS_DST_COLOR / ONE_MINUS_SRC_COLOR），必须走 drawKeepBlend。
        if (u == 68 && v == 94 && width == 16 && height == 16) {
            return drawKeepBlend(crosshairAttackFull(), zLevel, x, y, 16, 16);
        }
        if (u == 36 && v == 94 && width == 16 && height == 4) {
            return drawKeepBlend(crosshairAttackBackground(), zLevel, x, y, 16, 4);
        }
        if (u == 52 && v == 94 && height == 4 && width > 0 && width <= 16) {
            return drawKeepBlend(crosshairAttackProgress(), zLevel, x, y, width, 4);
        }
        return false;
    }

    /** 准星：1.21.2 是 15x15 画在 (w/2-7.5, h/2-7.5)，1.12.2 是 16x16 画在 (w/2-7, h/2-7)，中心一致。 */
    private static boolean drawCrosshair(float zLevel, int x, int y) {
        GuiSprite sprite = crosshair();
        if (sprite == null) {
            return false;
        }
        // 必须保留反色混合，否则材质包的白色十字会直接白着画出来
        sprite.drawKeepBlend(x + 0.5F, y + 0.5F, 15, 15, zLevel);
        logOnce(sprite);
        return true;
    }

    /**
     * 反色混合下整图缩放绘制。
     *
     * <p>准星和准星侧攻击指示器是原版唯一一处「反色混合」：{@code out = src + dst - 2*src*dst}，
     * 于是**纯黑是透明**、纯白是反色。材质包的这几张图恰好是 <b>RGB 无 alpha</b>（实测
     * {@code crosshair.png} 与 {@code crosshair_attack_indicator_*.png} 的 IHDR colortype 都是 2），
     * 透明区被填成不透明黑——只有在这个混合下黑才会消失。走 {@link #draw} 换成 alpha 混合就会
     * 得到「硬边黑方块 + 中间一条药丸」，这正是用户报的「攻击冷却没有透明」。
     */
    private static boolean drawKeepBlend(GuiSprite sprite, float zLevel, int x, int y, int width, int height) {
        if (sprite == null) {
            return false;
        }
        sprite.drawKeepBlend(x, y, width, height, zLevel);
        logOnce(sprite);
        return true;
    }

    private static boolean draw(GuiSprite sprite, float zLevel, int x, int y, int width, int height) {
        if (sprite == null) {
            return false;
        }
        sprite.draw(x, y, width, height, zLevel);
        logOnce(sprite);
        return true;
    }

    /** 经验条/跳跃条的进度是从画布左边裁 width 宽的一条（原版就是 (0,69,w,5)）。 */
    private static boolean drawBar(GuiSprite sprite, float zLevel, int x, int y, int width) {
        if (sprite == null) {
            return false;
        }
        sprite.drawRegion(x, y, width, BAR_HEIGHT, 0, 0, BAR_CANVAS_WIDTH, BAR_HEIGHT, zLevel);
        logOnce(sprite);
        return true;
    }

    /** 攻击指示器：18x18 画布底部的高 height 那一条。 */
    private static boolean drawAttackProgress(int x, int y, int height, float zLevel) {
        GuiSprite sprite = attackProgress();
        if (sprite == null) {
            return false;
        }
        sprite.drawRegion(x, y, ATTACK_CANVAS, height, 0, ATTACK_CANVAS - height,
                ATTACK_CANVAS, ATTACK_CANVAS, zLevel);
        logOnce(sprite);
        return true;
    }

    private static void logOnce(GuiSprite sprite) {
        if (loggedOnce) {
            return;
        }
        loggedOnce = true;
        LOGGER.info("[tooltipfix] hud sprites ACTIVE (pack={})", sprite.packId());
    }

    private static GuiSprite hotbar() {
        if (hotbar == null) {
            hotbar = GuiSprite.of(HOTBAR);
        }
        return hotbar;
    }

    private static GuiSprite hotbarSelection() {
        if (hotbarSelection == null) {
            hotbarSelection = GuiSprite.of(HOTBAR_SELECTION);
        }
        return hotbarSelection;
    }

    private static GuiSprite offhandLeft() {
        if (offhandLeft == null) {
            offhandLeft = GuiSprite.of(OFFHAND_LEFT);
        }
        return offhandLeft;
    }

    private static GuiSprite offhandRight() {
        if (offhandRight == null) {
            offhandRight = GuiSprite.of(OFFHAND_RIGHT);
        }
        return offhandRight;
    }

    private static GuiSprite xpBackground() {
        if (xpBackground == null) {
            xpBackground = GuiSprite.of(XP_BACKGROUND);
        }
        return xpBackground;
    }

    private static GuiSprite xpProgress() {
        if (xpProgress == null) {
            xpProgress = GuiSprite.of(XP_PROGRESS);
        }
        return xpProgress;
    }

    private static GuiSprite jumpBackground() {
        if (jumpBackground == null) {
            jumpBackground = GuiSprite.of(JUMP_BACKGROUND);
        }
        return jumpBackground;
    }

    private static GuiSprite jumpProgress() {
        if (jumpProgress == null) {
            jumpProgress = GuiSprite.of(JUMP_PROGRESS);
        }
        return jumpProgress;
    }

    private static GuiSprite attackBackground() {
        if (attackBackground == null) {
            attackBackground = GuiSprite.of(ATTACK_BACKGROUND);
        }
        return attackBackground;
    }

    private static GuiSprite attackProgress() {
        if (attackProgress == null) {
            attackProgress = GuiSprite.of(ATTACK_PROGRESS);
        }
        return attackProgress;
    }

    private static GuiSprite crosshairAttackBackground() {
        if (crosshairAttackBackground == null) {
            crosshairAttackBackground = GuiSprite.of(CROSSHAIR_ATTACK_BACKGROUND);
        }
        return crosshairAttackBackground;
    }

    private static GuiSprite crosshairAttackProgress() {
        if (crosshairAttackProgress == null) {
            crosshairAttackProgress = GuiSprite.of(CROSSHAIR_ATTACK_PROGRESS);
        }
        return crosshairAttackProgress;
    }

    private static GuiSprite crosshairAttackFull() {
        if (crosshairAttackFull == null) {
            crosshairAttackFull = GuiSprite.of(CROSSHAIR_ATTACK_FULL);
        }
        return crosshairAttackFull;
    }

    private static GuiSprite crosshair() {
        if (crosshair == null) {
            crosshair = GuiSprite.of(CROSSHAIR);
        }
        return crosshair;
    }

    public static void reset() {
        hotbar = null;
        hotbarSelection = null;
        offhandLeft = null;
        offhandRight = null;
        xpBackground = null;
        xpProgress = null;
        jumpBackground = null;
        jumpProgress = null;
        attackBackground = null;
        attackProgress = null;
        crosshairAttackBackground = null;
        crosshairAttackProgress = null;
        crosshairAttackFull = null;
        crosshair = null;
    }
}
