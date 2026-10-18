package meow.tooltipfix.client;

import net.minecraft.util.ResourceLocation;

/**
 * 槽位高亮框（鼠标指着的格子外面那圈白框）。
 *
 * <p>1.12.2 有两处画它，颜色都是 {@code -2130706433}（0x80FFFFFF，50% 白）：
 * <ul>
 *   <li>{@code GuiContainer.drawScreen} 里一次 16x16 的 {@code drawGradientRect}（常态路径）</li>
 *   <li>{@code GuiContainer.drawSlot} 里一次 16x16 的 {@code Gui.drawRect}（某些界面走这条）</li>
 * </ul>
 * 两者都按「槽位左下角 -4」画成 24x24：材质包的 {@code container/slot_highlight_*} 画布就是 24x24，
 * 原版 16x16 只是里面那圈，直接用原版矩形会把外发光切掉。
 */
public final class SlotSprites {

    /** 原版 0x80FFFFFF。 */
    private static final int HIGHLIGHT_COLOR = -2130706433;
    private static final int SLOT_SIZE = 16;
    private static final int SPRITE_SIZE = 24;
    private static final int OVERHANG = 4;

    private static final ResourceLocation FRONT =
            new ResourceLocation("minecraft", "container/slot_highlight_front");
    private static final ResourceLocation BACK =
            new ResourceLocation("minecraft", "container/slot_highlight_back");

    private static GuiSprite frontSprite;
    private static GuiSprite backSprite;
    private static boolean loggedOnce;

    private SlotSprites() {
    }

    public static boolean gradient(float zLevel, int left, int top, int right, int bottom,
                                   int colorFrom, int colorTo) {
        if (colorFrom != HIGHLIGHT_COLOR || colorTo != HIGHLIGHT_COLOR) {
            return false;
        }
        if (Math.abs(right - left) != SLOT_SIZE || Math.abs(bottom - top) != SLOT_SIZE) {
            return false;
        }
        return drawSlotHighlight(Math.min(left, right), Math.min(top, bottom), zLevel);
    }

    public static boolean rect(int left, int top, int right, int bottom, int color) {
        if (color != HIGHLIGHT_COLOR) {
            return false;
        }
        if (Math.abs(right - left) != SLOT_SIZE || Math.abs(bottom - top) != SLOT_SIZE) {
            return false;
        }
        // Gui.drawRect 的 z 是硬编码 0
        return drawSlotHighlight(Math.min(left, right), Math.min(top, bottom), 0.0F);
    }

    private static boolean drawSlotHighlight(int x, int y, float zLevel) {
        // front 是 1.20 里盖在物品上面的那层；back 是垫在物品下面的。材质包通常两个都给，
        // 优先 front（与 1.20.1 版行为一致），缺了再退回 back。
        GuiSprite sprite = front();
        if (sprite == null) {
            sprite = back();
        }
        if (sprite == null) {
            return false;
        }
        sprite.draw(x - OVERHANG, y - OVERHANG, SPRITE_SIZE, SPRITE_SIZE, zLevel);
        if (!loggedOnce) {
            loggedOnce = true;
            org.apache.logging.log4j.LogManager.getLogger("tooltipfix")
                    .info("[tooltipfix] slot highlight sprite ACTIVE (pack={})", sprite.packId());
        }
        return true;
    }

    private static GuiSprite front() {
        if (frontSprite == null) {
            frontSprite = GuiSprite.of(FRONT);
        }
        return frontSprite;
    }

    private static GuiSprite back() {
        if (backSprite == null) {
            backSprite = GuiSprite.of(BACK);
        }
        return backSprite;
    }

    public static void reset() {
        frontSprite = null;
        backSprite = null;
    }
}
