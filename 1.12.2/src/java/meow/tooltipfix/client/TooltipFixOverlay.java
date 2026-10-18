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
 * <p>设置按钮**不**随字体引擎开关消失：引擎关掉之后还得靠它再打开。</p>
 */
public final class TooltipFixOverlay {

    private static final int BTN_W = 110;
    private static final int BTN_H = 16;
    private static final int BTN_MARGIN = 4;

    private static final int COLOR_BORDER = 0xFF9A9A9A;
    private static final int COLOR_FILL = 0xC0202226;
    private static final int COLOR_FILL_HOVER = 0xC0343A44;
    private static final int COLOR_TEXT = 0xFFF0F0F0;

    /** 上一帧鼠标左键是否按着（用来抓「按下」这一瞬间，避免按住期间反复打开界面）。 */
    private static boolean pressed;

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
        // 滑块自己会判断「当前材质包有没有矢量字体」，没有就整块不画
        FontScaleSlider.draw(screen, mouseX, mouseY);

        ScaledResolution scaled = new ScaledResolution(mc);
        int x = BTN_MARGIN;
        int y = scaled.func_78328_b() - BTN_H - BTN_MARGIN;
        boolean hover = mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;

        boolean held = Mouse.isButtonDown(0);
        if (held && !pressed) {
            pressed = true;
            if (hover) {
                // 直接换屏：本方法已经是 drawScreen 的最后一步（注入点在 RETURN 前），
                // 原版界面这一帧要画的东西都画完了。
                mc.func_147108_a(new GuiTooltipFixSettings(screen));
                return;
            }
        } else if (!held) {
            pressed = false;
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
