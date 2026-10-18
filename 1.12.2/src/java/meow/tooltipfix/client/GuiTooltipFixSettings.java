package meow.tooltipfix.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * TooltipFix 的设置界面：字体兼容引擎开关、隐藏容器标题开关、CozyUI 预设开关、字号滑块、
 * 字形 X/Y 偏移。
 *
 * <p>入口在资源包（材质包）界面左下角的小按钮上——那里本来就是「管资源包」的地方，
 * 而本 mod 的开关都跟材质包内容有关。屏幕本身是普通 {@link GuiScreen}，
 * 按钮点击走原版流程，不需要任何字节码注入。</p>
 *
 * <p>所有改动都立刻生效并写盘（{@link TfSettings}）：关掉字体引擎时这屏幕上自己的字
 * 当帧就会变回 1.12.2 原生 8px，算是所见即所得。</p>
 *
 * <p><b>偏移是干什么的：</b>材质包只提供拉丁字母的 ttf 时，中文会逐字回退到原版 unifont，
 * 两套字的字高/基线往往对不齐，混排看起来一高一低。这里的 X/Y 只平移 HD 字形本身，
 * 不动前进宽度，所以不会把排版挤乱。</p>
 */
public final class GuiTooltipFixSettings extends GuiScreen {

    private static final int ID_DONE = 0;
    private static final int ID_FONT = 1;
    private static final int ID_TITLES = 2;
    private static final int ID_PRESET = 3;
    private static final int ID_OFFX_DEC = 4;
    private static final int ID_OFFX_INC = 5;
    private static final int ID_OFFY_DEC = 6;
    private static final int ID_OFFY_INC = 7;
    private static final int ID_OFF_RESET = 8;
    private static final int ID_SP_DEC = 9;
    private static final int ID_SP_INC = 10;

    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int GAP = 2;
    private static final int SLIDER_W = 200;
    private static final int SLIDER_H = 22;
    private static final int STEP_W = 20;
    private static final int STEP_H = 18;

    private static final int COLOR_TITLE = 0xFFFFFF;
    private static final int COLOR_HINT = 0xA0A0A0;
    private static final int COLOR_WARN = 0xE0B040;

    private final GuiScreen parent;

    /**
     * 本界面被打开的时刻。
     *
     * <p>我们是从资源包界面的 drawScreen 里换屏的（{@link TooltipFixOverlay}），而原版那一帧的
     * {@code handleMouseInput} 还没跑——同一次点击会被这个新界面再收到一次。挡掉最初 200ms
     * 的点击，避免「点设置按钮」变成「顺手点中设置界面里的某个开关」。</p>
     */
    private final long openedAt = System.currentTimeMillis();

    private GuiButton fontButton;
    private GuiButton titlesButton;
    private GuiButton presetButton;
    private GuiButton offsetResetButton;

    private int sliderX;
    private int sliderY;
    private int offXRowY;
    private int offYRowY;
    private int spacingRowY;
    private int offResetY;
    private int hintY;

    public GuiTooltipFixSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void func_73866_w_() {
        TfSettings.load();
        this.field_146292_n.clear();
        int cx = this.field_146294_l / 2;
        int x = cx - BUTTON_W / 2;
        // 1.12.2 的 GUI 逻辑高度只有 270（guiScale 4 @1080p），排版得挤一点才放得下
        int top = Math.max(20, this.field_146295_m / 2 - 115);

        fontButton = new GuiButton(ID_FONT, x, top, BUTTON_W, BUTTON_H, "");
        titlesButton = new GuiButton(ID_TITLES, x, top + (BUTTON_H + GAP), BUTTON_W, BUTTON_H, "");
        presetButton = new GuiButton(ID_PRESET, x, top + (BUTTON_H + GAP) * 2, BUTTON_W, BUTTON_H, "");
        this.field_146292_n.add(fontButton);
        this.field_146292_n.add(titlesButton);
        this.field_146292_n.add(presetButton);

        sliderX = cx - SLIDER_W / 2;
        sliderY = top + (BUTTON_H + GAP) * 3 + 4;

        int rowStep = STEP_H + GAP;
        offXRowY = sliderY + SLIDER_H + 4;
        offYRowY = offXRowY + rowStep;
        spacingRowY = offYRowY + rowStep;
        offResetY = spacingRowY + rowStep + 2;

        this.field_146292_n.add(new GuiButton(ID_OFFX_DEC, x, offXRowY, STEP_W, STEP_H, "-"));
        this.field_146292_n.add(new GuiButton(ID_OFFX_INC, x + BUTTON_W - STEP_W, offXRowY, STEP_W, STEP_H, "+"));
        this.field_146292_n.add(new GuiButton(ID_OFFY_DEC, x, offYRowY, STEP_W, STEP_H, "-"));
        this.field_146292_n.add(new GuiButton(ID_OFFY_INC, x + BUTTON_W - STEP_W, offYRowY, STEP_W, STEP_H, "+"));
        this.field_146292_n.add(new GuiButton(ID_SP_DEC, x, spacingRowY, STEP_W, STEP_H, "-"));
        this.field_146292_n.add(new GuiButton(ID_SP_INC, x + BUTTON_W - STEP_W, spacingRowY, STEP_W, STEP_H, "+"));
        offsetResetButton = new GuiButton(ID_OFF_RESET, x, offResetY, BUTTON_W, STEP_H, "偏移 / 间距归零");
        this.field_146292_n.add(offsetResetButton);

        hintY = offResetY + STEP_H + 6;
        this.field_146292_n.add(new GuiButton(ID_DONE, x, this.field_146295_m - 26, BUTTON_W, BUTTON_H, "完成"));
        refreshLabels();
    }

    @Override
    protected void func_146284_a(GuiButton button) {
        switch (button.field_146127_k) {
            case ID_FONT:
                TfSettings.setFontEngine(!TfSettings.fontEngine());
                break;
            case ID_TITLES:
                TfSettings.setHideTitles(!TfSettings.hideTitles());
                break;
            case ID_PRESET:
                TfSettings.setPreset(!TfSettings.preset());
                break;
            case ID_OFFX_DEC:
                shiftOffset(-1, 0);
                break;
            case ID_OFFX_INC:
                shiftOffset(1, 0);
                break;
            case ID_OFFY_DEC:
                shiftOffset(0, -1);
                break;
            case ID_OFFY_INC:
                shiftOffset(0, 1);
                break;
            case ID_OFF_RESET:
                shiftOffset(-TfSettings.offsetX(), -TfSettings.offsetY());
                if (TfSettings.spacing() != 0) {
                    shiftSpacing(-TfSettings.spacing());
                }
                break;
            case ID_SP_DEC:
                shiftSpacing(-1);
                break;
            case ID_SP_INC:
                shiftSpacing(1);
                break;
            case ID_DONE:
                TfSettings.save();
                this.field_146297_k.func_147108_a(this.parent);
                return;
            default:
                return;
        }
        refreshLabels();
    }

    /** 挪一格偏移：只改内存，同时落盘一次（这里点击频率低，不需要像滑块那样去抖）。 */
    private void shiftOffset(int dx, int dy) {
        TfSettings.setOffset(TfSettings.offsetX() + dx, TfSettings.offsetY() + dy);
        TfSettings.save();
    }

    /** 挪一格字间距：同上，点一次存一次（前进宽度是现算的，不需要重载字体）。 */
    private void shiftSpacing(int delta) {
        TfSettings.setSpacing(TfSettings.spacing() + delta);
        TfSettings.save();
    }

    @Override
    public void func_73863_a(int mouseX, int mouseY, float partialTicks) {
        this.func_146276_q_();
        int cx = this.field_146294_l / 2;
        this.func_73732_a(this.field_146289_q, "TooltipFix 设置", cx, 8, COLOR_TITLE);

        // 滑块自己读鼠标（见 FontScaleSlider），所以它先吃这次点击，剩下的才轮到按钮
        FontScaleSlider.draw(this, sliderX, sliderY, SLIDER_W, SLIDER_H, mouseX, mouseY);
        super.func_73863_a(mouseX, mouseY, partialTicks);

        this.func_73732_a(this.field_146289_q, "X 偏移：" + signed(TfSettings.offsetX()),
                cx, offXRowY + 5, COLOR_TITLE);
        this.func_73732_a(this.field_146289_q, "Y 偏移：" + signed(TfSettings.offsetY()),
                cx, offYRowY + 5, COLOR_TITLE);
        this.func_73732_a(this.field_146289_q, "字间距：" + signed(TfSettings.spacing()),
                cx, spacingRowY + 5, COLOR_TITLE);

        if (HdFont.hasTtf()) {
            this.func_73732_a(this.field_146289_q, "滑滑块调字号（松手保存）",
                    cx, hintY, COLOR_HINT);
        } else if (!HdFont.enabled()) {
            this.func_73732_a(this.field_146289_q, "字体引擎已关闭：正在使用 1.12.2 原生 8px 字体",
                    cx, hintY, COLOR_HINT);
        } else {
            this.func_73732_a(this.field_146289_q, "当前材质包没有矢量字体（ttf），字号不可调",
                    cx, hintY, COLOR_WARN);
        }
        this.func_73732_a(this.field_146289_q, "X/Y 只挪字形，间距改字与字的距离；归零可一键还原",
                cx, hintY + 11, COLOR_HINT);
        this.func_73732_a(this.field_146289_q, "CozyUI 预设 = 引擎开 + 字号 100% + 隐藏标题 + 全零",
                cx, hintY + 22, COLOR_HINT);
        this.func_73732_a(this.field_146289_q, "config/tooltipfix-hdfont.txt",
                cx, hintY + 33, COLOR_HINT);
    }

    @Override
    protected void func_73864_a(int mouseX, int mouseY, int mouseButton) {
        if (System.currentTimeMillis() - openedAt < 200L) {
            return;
        }
        super.func_73864_a(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void func_73869_a(char typedChar, int keyCode) {
        if (keyCode == 1) { // Esc：回原来的界面，别掉到主菜单
            TfSettings.save();
            this.field_146297_k.func_147108_a(this.parent);
            return;
        }
        super.func_73869_a(typedChar, keyCode);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private void refreshLabels() {
        if (fontButton != null) {
            fontButton.field_146126_j = "字体引擎：" + (TfSettings.fontEngine() ? "开" : "关");
        }
        if (titlesButton != null) {
            titlesButton.field_146126_j = "隐藏容器标题：" + (TfSettings.hideTitles() ? "开" : "关");
        }
        if (presetButton != null) {
            presetButton.field_146126_j = "CozyUI 预设：" + (TfSettings.preset() ? "开" : "关");
        }
        if (offsetResetButton != null) {
            boolean zero = TfSettings.offsetX() == 0 && TfSettings.offsetY() == 0
                    && TfSettings.spacing() == 0;
            offsetResetButton.field_146125_m = !zero; // 已经是 0 就藏起来，少一个可点的东西
        }
    }
}
