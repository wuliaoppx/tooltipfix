package meow.tooltipfix.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

/**
 * TooltipFix 的设置界面：字体兼容引擎开关、隐藏容器标题开关、CozyUI 预设开关，
 * 以及字号 / 字形 X 偏移 / 字形 Y 偏移 / 字间距四行「滑块 + 数字输入框」。
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
    private static final int ID_RESET = 8;

    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int GAP = 2;
    private static final int ROW_H = 20;
    private static final int ROW_STEP = 22;
    private static final int STEP_H = 18;

    private static final int COLOR_TITLE = 0xFFFFFF;
    private static final int COLOR_HINT = 0xA0A0A0;
    private static final int COLOR_WARN = 0xE0B040;

    /** 偏移 / 间距改完就落盘：这两项不重载字体，写盘代价可忽略。 */
    private static final Runnable SAVE = new Runnable() {
        @Override
        public void run() {
            TfSettings.save();
        }
    };

    private final GuiScreen parent;

    /** 字号：界面上按百分比显示，实际存的是 0.5~2.0 的倍率（见 {@link TfSettings#setScale(float)}）。 */
    private final NumberSlider scaleRow = new NumberSlider("字号 %", "%", 50, 200,
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
            }, true);

    private final NumberSlider offXRow = new NumberSlider("X 偏移", "",
            TfSettings.MIN_OFFSET, TfSettings.MAX_OFFSET,
            new NumberSlider.Sink() {
                @Override
                public int get() {
                    return TfSettings.offsetX();
                }

                @Override
                public void set(int value) {
                    TfSettings.setOffset(value, TfSettings.offsetY());
                }
            }, SAVE, true);

    private final NumberSlider offYRow = new NumberSlider("Y 偏移", "",
            TfSettings.MIN_OFFSET, TfSettings.MAX_OFFSET,
            new NumberSlider.Sink() {
                @Override
                public int get() {
                    return TfSettings.offsetY();
                }

                @Override
                public void set(int value) {
                    TfSettings.setOffset(TfSettings.offsetX(), value);
                }
            }, SAVE, true);

    private final NumberSlider spacingRow = new NumberSlider("字间距", "",
            TfSettings.MIN_SPACING, TfSettings.MAX_SPACING,
            new NumberSlider.Sink() {
                @Override
                public int get() {
                    return TfSettings.spacing();
                }

                @Override
                public void set(int value) {
                    TfSettings.setSpacing(value);
                }
            }, SAVE, true);

    private final NumberSlider[] rows = {scaleRow, offXRow, offYRow, spacingRow};

    /**
     * 本界面被打开的时刻。
     *
     * <p>我们是从资源包界面的 drawScreen 里换屏的（{@link TooltipFixOverlay}），而原版那一帧的
     * {@code handleMouseInput} 还没跑——同一次点击会被这个新界面再收到一次。挡掉最初 200ms
     * 的点击，避免「点设置按钮」变成「顺手点中设置界面里的某个开关」。</p>
     */
    private final long openedAt = System.currentTimeMillis();

    /** 见过一次「鼠标没按」之后才接受输入：滑块是轮询的，光看 openedAt 挡不住按着的鼠标。 */
    private boolean armed;

    private GuiButton fontButton;
    private GuiButton titlesButton;
    private GuiButton presetButton;
    private GuiButton resetButton;

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

        int rowsTop = top + (BUTTON_H + GAP) * 3 + 2;
        for (int i = 0; i < rows.length; i++) {
            rows[i].layout(x, rowsTop + i * ROW_STEP, BUTTON_W, ROW_H);
            rows[i].refresh();
        }

        offResetY = rowsTop + ROW_STEP * rows.length + 2;
        resetButton = new GuiButton(ID_RESET, x, offResetY, BUTTON_W, STEP_H, "全部恢复默认");
        this.field_146292_n.add(resetButton);

        hintY = offResetY + STEP_H + 3;
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
            case ID_RESET:
                TfSettings.resetAll(); // 内部已经 apply + save
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

    @Override
    public void func_73863_a(int mouseX, int mouseY, float partialTicks) {
        if (!Mouse.isButtonDown(0) && System.currentTimeMillis() - openedAt >= 200L) {
            armed = true;
        }
        this.func_146276_q_();
        int cx = this.field_146294_l / 2;
        this.func_73732_a(this.field_146289_q, "TooltipFix 设置", cx, 8, COLOR_TITLE);

        // 滑块自己读鼠标，画在按钮之前；没有矢量字体时字号那行整块不画（原版 8px 没得调）
        for (NumberSlider row : rows) {
            if (row == scaleRow && !HdFont.hasTtf()) {
                row.blur();
                continue;
            }
            row.draw(mouseX, mouseY, armed);
        }
        super.func_73863_a(mouseX, mouseY, partialTicks);

        if (HdFont.hasTtf()) {
            this.func_73732_a(this.field_146289_q, "拖滑块调字号（松手保存）",
                    cx, hintY, COLOR_HINT);
        } else if (!HdFont.enabled()) {
            this.func_73732_a(this.field_146289_q, "字体引擎已关闭：正在使用 1.12.2 原生 8px 字体",
                    cx, hintY, COLOR_HINT);
        } else {
            this.func_73732_a(this.field_146289_q, "当前材质包没有矢量字体（ttf），字号不可调",
                    cx, hintY, COLOR_WARN);
        }
        this.func_73732_a(this.field_146289_q, "拖滑块调值，或点右边数字框输入后回车",
                cx, hintY + 11, COLOR_HINT);
        this.func_73732_a(this.field_146289_q, "恢复默认 = 引擎开 + 隐藏标题 + 字号 100% + 全零",
                cx, hintY + 22, COLOR_HINT);
        this.func_73732_a(this.field_146289_q, "配置文件：config/tooltipfix-hdfont.txt",
                cx, hintY + 33, COLOR_HINT);
    }

    @Override
    public void func_73876_c() {
        super.func_73876_c();
        for (NumberSlider row : rows) {
            row.update();
        }
    }

    @Override
    protected void func_73864_a(int mouseX, int mouseY, int mouseButton) {
        if (!armed) {
            return;
        }
        boolean consumed = false;
        for (NumberSlider row : rows) {
            if (row.mouseClicked(mouseX, mouseY, mouseButton)) {
                consumed = true;
            }
        }
        if (consumed) {
            return;
        }
        super.func_73864_a(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void func_73869_a(char typedChar, int keyCode) {
        for (NumberSlider row : rows) {
            if (row.keyTyped(typedChar, keyCode)) {
                return;
            }
        }
        if (keyCode == 1) { // Esc：回原来的界面，别掉到主菜单
            TfSettings.save();
            this.field_146297_k.func_147108_a(this.parent);
            return;
        }
        super.func_73869_a(typedChar, keyCode);
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
        // 一键恢复默认，所以不需要置灰——灰着按一下也没坏处，但会让人以为坏了
        for (NumberSlider row : rows) {
            row.refresh();
        }
    }
}
