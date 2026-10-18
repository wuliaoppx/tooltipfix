package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Mouse;

/**
 * 资源包（材质包）界面上的两块自绘控件：左上角的字号滑块 + 左下角的「TooltipFix 设置」按钮。
 *
 * <p>为什么全是自绘而不是往 {@code buttonList} 里塞 {@link net.minecraft.client.gui.GuiButton}：
 * 资源包界面的 {@code initGui} 是原版代码、{@code buttonList} 又是 protected，塞控件得再注入一次；
 * 而 drawScreen 末尾本来就有一个注入点（{@link meow.tooltipfix.core.ResourcePackUiTransformer}），
 * 自己画 + 自己读鼠标最省事。</p>
 *
 * <p>这里的滑块不带数字输入框：这个界面收不到我们转发的按键（没有注入 {@code keyTyped}），
 * 想要精确输入就点左下角进设置界面。</p>
 *
 * <p>设置按钮**不**随字体引擎开关消失：引擎关掉之后还得靠它再打开。</p>
 */
public final class TooltipFixOverlay {

    private static final int SLIDER_X = 4;
    private static final int SLIDER_Y = 5;
    private static final int SLIDER_W = 140;
    private static final int SLIDER_H = 20;

    private static final int BTN_W = 110;
    private static final int BTN_H = 16;
    private static final int BTN_MARGIN = 4;

    private static final int COLOR_BORDER = 0xFF9A9A9A;
    private static final int COLOR_FILL = 0xC0202226;
    private static final int COLOR_FILL_HOVER = 0xC0343A44;
    private static final int COLOR_TEXT = 0xFFF0F0F0;

    /** 界面上的字号滑块（与设置界面里那个共用 {@link TfSettings} 的存储）。 */
    private static final NumberSlider SCALE = new NumberSlider("字号", "%", 50, 200,
            new NumberSlider.Sink() {
                @Override
                public int get() {
                    return Math.round(TfSettings.scale() * 100.0F);
                }

                @Override
                public void set(int value) {
                    HdFont.setUserScale(value / 100.0F);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    HdFont.saveUserScale();
                }
            }, false);

    /** 上一帧鼠标左键是否按着（用来抓「按下」这一瞬间，避免按住期间反复打开界面）。 */
    private static boolean pressed;

    /** 见过一次鼠标松开没有：挡住「换屏那一下还按着鼠标」被滑块/按钮当成新点击。 */
    private static boolean released;

    private TooltipFixOverlay() {
    }

    /**
     * 资源包界面 drawScreen 的末尾（注入点）。抛异常由 {@link Hooks#resourcePackScreen} 兜底。
     */
    public static void draw(GuiScreen screen, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return;
        }
        boolean held = Mouse.isButtonDown(0);
        if (!held) {
            released = true;
            pressed = false;
        }

        // 滑块自己会判断「当前材质包有没有矢量字体」，没有就整块不画
        if (HdFont.hasTtf()) {
            SCALE.layout(SLIDER_X, SLIDER_Y, SLIDER_W, SLIDER_H);
            SCALE.draw(mouseX, mouseY, released);
        } else {
            SCALE.blur();
        }

        ScaledResolution scaled = new ScaledResolution(mc);
        int x = BTN_MARGIN;
        int y = scaled.func_78328_b() - BTN_H - BTN_MARGIN;
        boolean hover = mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;

        if (released && held && !pressed) {
            pressed = true;
            if (hover) {
                // 直接换屏：本方法已经是 drawScreen 的最后一步（注入点在 RETURN 前），
                // 原版界面这一帧要画的东西都画完了。
                mc.func_147108_a(new GuiTooltipFixSettings(screen));
                return;
            }
        }

        Gui.func_73734_a(x, y, x + BTN_W, y + BTN_H, hover ? COLOR_FILL_HOVER : COLOR_FILL);
        Gui.func_73734_a(x, y, x + BTN_W, y + 1, COLOR_BORDER);
        Gui.func_73734_a(x, y + BTN_H - 1, x + BTN_W, y + BTN_H, COLOR_BORDER);
        Gui.func_73734_a(x, y, x + 1, y + BTN_H, COLOR_BORDER);
        Gui.func_73734_a(x + BTN_W - 1, y, x + BTN_W, y + BTN_H, COLOR_BORDER);

        GuiRender.beginGui();
        FontRenderer font = mc.field_71466_p;
        if (font != null) {
            font.func_175063_a("TooltipFix 设置…", (float) (x + 6), (float) (y + 4), COLOR_TEXT);
        }
    }
}
