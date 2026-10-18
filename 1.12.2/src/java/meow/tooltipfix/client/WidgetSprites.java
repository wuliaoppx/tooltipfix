package meow.tooltipfix.client;

import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 按钮底板与滑条手柄。
 *
 * <p>1.12.2 的 {@code GuiButton.drawButton} 把底板拆成左右两半各 blit 一次：
 * <pre>
 * this.b(x,              y, 0,                 46 + j * 20, w / 2, h);   // 左半
 * this.b(x + w / 2,      y, 200 - w / 2,       46 + j * 20, w / 2, h);   // 右半
 * </pre>
 * 其中 {@code j}：0 = 禁用(46)、1 = 常态(66)、2 = 悬停(86)。材质包给的是一整张
 * {@code widget/button*}（画布 200x20、border 8 的九宫格），所以必须把两半拼回一整块再画。
 *
 * <p>做法：第一次看到「u==0 且 v∈{46,66,86}」时先按住不画；下一次 blit 如果正好是它的右半
 * （同 v/同宽高、u==200-w/2、x 接在右面）就拼起来画 sprite；否则把按住的那半原样补画出去再继续。
 * 因为两半在字节码里就是连续两条指令，这个「等一下」不会跨到别的东西上。
 *
 * <p>例外：{@code GuiSlider} 的旋钮也是这么对半画的（{@code (0,66,4,20)} + {@code (196,66,4,20)}），
 * 模式与按钮完全一样，只能靠「宽度 4 + 第二半 u==196」区分，这种情况画 {@code widget/slider_handle}。
 */
public final class WidgetSprites {

    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");

    /** 原版底图画布宽度，右半的 u 由它减出来。 */
    private static final int SHEET_WIDTH = 200;
    private static final int SLIDER_HANDLE_WIDTH = 4;

    private static final ResourceLocation BUTTON = new ResourceLocation("minecraft", "widget/button");
    private static final ResourceLocation BUTTON_DISABLED =
            new ResourceLocation("minecraft", "widget/button_disabled");
    private static final ResourceLocation BUTTON_HIGHLIGHTED =
            new ResourceLocation("minecraft", "widget/button_highlighted");
    private static final ResourceLocation SLIDER_HANDLE =
            new ResourceLocation("minecraft", "widget/slider_handle");

    private static boolean pending;
    private static int pendingX;
    private static int pendingY;
    private static int pendingU;
    private static int pendingV;
    private static int pendingW;
    private static int pendingH;
    private static float pendingZ;

    private static GuiSprite buttonSprite;
    private static GuiSprite disabledSprite;
    private static GuiSprite highlightedSprite;
    private static GuiSprite sliderSprite;
    private static boolean loggedOnce;

    private WidgetSprites() {
    }

    public static boolean blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (pending) {
            boolean pairs = v == pendingV && width == pendingW && height == pendingH
                    && u == SHEET_WIDTH - width && x == pendingX + width && y == pendingY;
            if (pairs) {
                pending = false;
                return drawPair(pendingX, pendingY, pendingV, width, height, u, zLevel);
            }
            flush();
        }
        if (u == 0 && (v == 46 || v == 66 || v == 86)) {
            pending = true;
            pendingX = x;
            pendingY = y;
            pendingU = u;
            pendingV = v;
            pendingW = width;
            pendingH = height;
            pendingZ = zLevel;
            return true;
        }
        return false;
    }

    /** 把按住的那半原样补画出去（材质包缺图、或者它根本不是一对的时候）。 */
    public static void flush() {
        if (!pending) {
            return;
        }
        pending = false;
        GuiRender.rawBlit(pendingX, pendingY, pendingU, pendingV, pendingW, pendingH, pendingZ);
    }

    private static boolean drawPair(int x, int y, int v, int halfWidth, int height,
                                    int secondU, float zLevel) {
        int fullWidth = halfWidth * 2;
        boolean slider = halfWidth == SLIDER_HANDLE_WIDTH && height == 20
                && secondU == SHEET_WIDTH - SLIDER_HANDLE_WIDTH;
        GuiSprite sprite = slider ? slider() : spriteFor(v);
        if (sprite == null) {
            GuiRender.rawBlit(x, y, 0, v, halfWidth, height, zLevel);
            GuiRender.rawBlit(x + halfWidth, y, secondU, v, halfWidth, height, zLevel);
            return true;
        }
        sprite.draw(x, y, fullWidth, height, zLevel);
        if (!loggedOnce) {
            loggedOnce = true;
            LOGGER.info("[tooltipfix] button sprites ACTIVE (pack={})", sprite.packId());
        }
        return true;
    }

    private static GuiSprite spriteFor(int v) {
        if (v == 46) {
            if (disabledSprite == null) {
                disabledSprite = GuiSprite.of(BUTTON_DISABLED);
            }
            return disabledSprite;
        }
        if (v == 86) {
            if (highlightedSprite == null) {
                highlightedSprite = GuiSprite.of(BUTTON_HIGHLIGHTED);
            }
            return highlightedSprite;
        }
        if (buttonSprite == null) {
            buttonSprite = GuiSprite.of(BUTTON);
        }
        return buttonSprite;
    }

    private static GuiSprite slider() {
        if (sliderSprite == null) {
            sliderSprite = GuiSprite.of(SLIDER_HANDLE);
        }
        return sliderSprite;
    }

    public static void reset() {
        pending = false;
        buttonSprite = null;
        disabledSprite = null;
        highlightedSprite = null;
        sliderSprite = null;
    }
}
