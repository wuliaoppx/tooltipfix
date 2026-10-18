package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

/**
 * 材质包（资源包）界面左上角的「字号」滑块。
 *
 * <p>HD 字体是按材质包 {@code default.json} 里 ttf provider 的 {@code size} 栅格化的，
 * 同一个包在不同人眼里「太大」或「太细」——所以给个能当场拖的滑块，不逼人去改 json。
 * 值存在 {@code config/tooltipfix-hdfont.txt}，见 {@link HdFont#setUserScale(float)}。</p>
 *
 * <p>为什么不继承 {@code GuiButton}：资源包界面的 {@code initGui} 是原版代码，1.12.2 的
 * {@code buttonList} 字段又是 protected，往里塞控件得额外注入；而 drawScreen 末尾已经有一个
 * 注入点（{@code ResourcePackUiTransformer}），自己画 + 自己读鼠标最省事，也不会跟原版控件
 * 抢点击。没人调用 {@code mouseClicked}/{@code mouseReleased} 也无所谓——状态由本类自己维护。</p>
 */
public final class FontScaleSlider {

    private static final int X = 4;
    private static final int Y = 5;
    private static final int WIDTH = 112;
    private static final int HEIGHT = 22;

    /** 滑轨左右内缩，留出边框和一点点边距。 */
    private static final int PAD = 5;

    private static final float MIN_VALUE = TfSettings.MIN_SCALE;
    private static final float MAX_VALUE = TfSettings.MAX_SCALE;

    /** 步进 5%：拖起来有段落感，也不会因为浮点抖动写出一堆无意义的配置值。 */
    private static final float STEP = 0.05F;

    private static final int COLOR_BORDER = 0xFF9A9A9A;
    private static final int COLOR_FILL = 0xC0202226;
    private static final int COLOR_FILL_ACTIVE = 0xC0343A44;
    private static final int COLOR_TRACK = 0xFF0E0E0E;
    private static final int COLOR_TRACK_DONE = 0xFF5A8FD6;
    private static final int COLOR_KNOB = 0xFFEDEDED;
    private static final int COLOR_TEXT = 0xFFF0F0F0;

    /** 正在拖（鼠标按住过滑块，且还没松手）。 */
    private static boolean dragging;

    private FontScaleSlider() {
    }

    /**
     * 在材质包界面左上角画（默认位置，见 {@link #draw(GuiScreen, int, int, int, int, int, int)}）。
     */
    public static void draw(GuiScreen screen, int mouseX, int mouseY) {
        draw(screen, X, Y, WIDTH, HEIGHT, mouseX, mouseY);
    }

    /**
     * 在任意位置画一个字号滑块（设置界面也用它，省得写两套）。
     *
     * <p>当前材质包没有矢量字体时什么都不画也不吃点击：原版 8px 管线没得调，画出来只会误导人。</p>
     *
     * @return true = 这块控件收下了这次点击（调用方别处别再把同一次点击当按钮用）
     */
    public static boolean draw(GuiScreen screen, int x, int y, int width, int height,
                               int mouseX, int mouseY) {
        if (!HdFont.hasTtf()) {
            dragging = false;
            return false;
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return false;
        }
        int trackLeft = x + PAD;
        int trackRight = x + width - PAD;
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        boolean held = Mouse.isButtonDown(0);

        if (held && (hover || dragging)) {
            float t = (mouseX - trackLeft) / (float) (trackRight - trackLeft);
            t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
            HdFont.setUserScale(quantize(MIN_VALUE + t * (MAX_VALUE - MIN_VALUE)));
            dragging = true;
        } else if (dragging) {
            // 松手：落盘一次（拖动过程中只改内存，免得每帧写盘）
            dragging = false;
            HdFont.saveUserScale();
        }

        float value = HdFont.userScale();
        boolean active = hover || dragging;

        // 底板 + 四边
        Gui.func_73734_a(x, y, x + width, y + height, active ? COLOR_FILL_ACTIVE : COLOR_FILL);
        Gui.func_73734_a(x, y, x + width, y + 1, COLOR_BORDER);
        Gui.func_73734_a(x, y + height - 1, x + width, y + height, COLOR_BORDER);
        Gui.func_73734_a(x, y, x + 1, y + height, COLOR_BORDER);
        Gui.func_73734_a(x + width - 1, y, x + width, y + height, COLOR_BORDER);

        // 滑轨 + 已走过的部分 + 滑块
        int trackTop = y + height - 8;
        int knobX = trackLeft
                + Math.round((value - MIN_VALUE) / (MAX_VALUE - MIN_VALUE) * (trackRight - trackLeft));
        Gui.func_73734_a(trackLeft, trackTop, trackRight, trackTop + 3, COLOR_TRACK);
        Gui.func_73734_a(trackLeft, trackTop, knobX, trackTop + 3, COLOR_TRACK_DONE);
        Gui.func_73734_a(knobX - 2, trackTop - 3, knobX + 2, trackTop + 6, COLOR_KNOB);

        // 文字：drawRect 会把当前颜色留成底下那个色，先把 GL 状态摆回去
        GuiRender.beginGui();
        FontRenderer font = mc.field_71466_p;
        String label = "字号 " + Math.round(value * 100.0F) + "%";
        if (font != null) {
            font.func_175063_a(label, (float) (x + PAD), (float) (y + 3), COLOR_TEXT);
        }
        return hover || dragging;
    }

    private static float quantize(float value) {
        float snapped = Math.round(value / STEP) * STEP;
        if (snapped < MIN_VALUE) {
            return MIN_VALUE;
        }
        return snapped > MAX_VALUE ? MAX_VALUE : snapped;
    }
}
