package meow.tooltipfix.client;

import com.google.common.base.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Mouse;

/**
 * 一个「滑块 + 数字输入框」的行控件，整数字面值。
 *
 * <p>为什么不用 {@code GuiSlider} / {@code GuiButton}：这些行列都不是原版界面的按钮，而是我们自己
 * 画在 drawScreen 里的（资源包界面的注入点只给了绘制时机，拿不到 buttonList，见
 * {@link TooltipFixOverlay}）。自己画就得自己读鼠标：滑轨靠 {@link Mouse#isButtonDown(int)} 轮询，
 * 数字框交给原版 {@link GuiTextField}，由调用方把 {@code mouseClicked}/{@code keyTyped} 转发进来。</p>
 *
 * <p>拖动过程中只改内存（{@link Sink#set(int)}），松手和输入框回车才调 {@code onCommit}
 * ——字号那档 {@code set} 会重建字形图集，每帧都落盘+重载会卡。</p>
 *
 * <p>{@code withField=false} 时不带输入框，只在右端显示当前值，给资源包界面上那个不接收键盘的
 * 浮层用（那里没有 GuiScreen 转发按键）。</p>
 */
public final class NumberSlider {

    /** 取值入口：读当前值、写新值（写只落内存，持久化交给 {@link #onCommit}）。 */
    public interface Sink {
        int get();

        void set(int value);
    }

    /** 只允许整数（可带负号）的输入过滤器，避免用户在框里打进字母之后又被悄悄改掉。 */
    private static final Predicate<String> NUMBER_ONLY = new Predicate<String>() {
        @Override
        public boolean apply(String input) {
            if (input == null || input.isEmpty() || "-".equals(input)) {
                return true; // 中间态：刚清空、刚敲一个负号
            }
            if (input.length() > 4) {
                return false;
            }
            int start = input.charAt(0) == '-' ? 1 : 0;
            if (start == input.length()) {
                return false;
            }
            for (int i = start; i < input.length(); i++) {
                if (input.charAt(i) < '0' || input.charAt(i) > '9') {
                    return false;
                }
            }
            return true;
        }
    };

    private static final int LABEL_W = 46;
    private static final int PAD = 4;
    private static final int FIELD_W = 38;
    private static final int FIELD_H = 16;
    private static final int TRACK_H = 3;
    private static final int KNOB_W = 4;
    private static final int TEXT_H = 8;

    private static final int COLOR_BORDER = 0xFF9A9A9A;
    private static final int COLOR_FILL = 0xC0202226;
    private static final int COLOR_FILL_ACTIVE = 0xC0343A44;
    private static final int COLOR_TRACK = 0xFF0E0E0E;
    private static final int COLOR_TRACK_DONE = 0xFF5A8FD6;
    private static final int COLOR_KNOB = 0xFFEDEDED;
    private static final int COLOR_TEXT = 0xFFF0F0F0;

    private final String label;
    /** 值后缀（例如字号的 "%"）。空串时正数显示成 "+3"，负数原样。 */
    private final String unit;
    private final int min;
    private final int max;
    private final Sink sink;
    private final Runnable onCommit;
    private final boolean withField;

    private int x;
    private int y;
    private int w;
    private int h;

    /** 鼠标正按在这条滑轨上（还没松手）。 */
    private boolean dragging;

    private GuiTextField field;

    /**
     * @param onCommit 松手 / 输入框回车后调一次，通常是落盘
     * @param withField 是否在右端放一个可输入的数字框
     */
    public NumberSlider(String label, String unit, int min, int max,
                        Sink sink, Runnable onCommit, boolean withField) {
        this.label = label;
        this.unit = unit == null ? "" : unit;
        this.min = min;
        this.max = max;
        this.sink = sink;
        this.onCommit = onCommit;
        this.withField = withField;
    }

    /** 定位置（构造时没拿到的几何在第一次 layout 时用来创建输入框）。 */
    public void layout(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.w = width;
        this.h = height;
        if (!withField || field != null) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        FontRenderer font = mc == null ? null : mc.field_71466_p;
        if (font == null) {
            return;
        }
        field = new GuiTextField(0, font, fieldX(), fieldY(), FIELD_W, FIELD_H);
        field.func_146203_f(4);
        field.func_146184_c(true);
        field.func_146193_g(COLOR_TEXT);
        field.func_175205_a(NUMBER_ONLY);
        field.func_146180_a(Integer.toString(sink.get()));
        field.func_146195_b(false);
    }

    /**
     * 画这一行并处理拖动。
     *
     * @param acceptInput false = 只画不响应（界面刚被打开、当初那次点击还没松开时用，挡掉误拖）
     * @return true = 鼠标在这行上（调用方据此跳过同位置的其他控件）
     */
    public boolean draw(int mouseX, int mouseY, boolean acceptInput) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return false;
        }
        FontRenderer font = mc.field_71466_p;

        int fx = fieldX();
        int fy = fieldY();
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        boolean overField = withField && inside(mouseX, mouseY, fx, fy, FIELD_W, FIELD_H);
        boolean held = Mouse.isButtonDown(0);

        int value = sink.get();
        int trackLeft = x + LABEL_W + PAD;
        int trackRight = (withField ? fx : x + w - PAD - valueWidth(font, value)) - PAD;

        if (dragging) {
            if (!held) {
                dragging = false;
                commit();
            } else if (acceptInput) {
                setFromMouse(mouseX, trackLeft, trackRight);
            }
        } else if (acceptInput && held && hover && !overField) {
            dragging = true;
            blur(); // 拖动时把输入框的焦点收走，否则框里还是旧数字
            setFromMouse(mouseX, trackLeft, trackRight);
        }

        value = sink.get();
        boolean active = (hover && acceptInput) || dragging;

        Gui.func_73734_a(x, y, x + w, y + h, active ? COLOR_FILL_ACTIVE : COLOR_FILL);
        Gui.func_73734_a(x, y, x + w, y + 1, COLOR_BORDER);
        Gui.func_73734_a(x, y + h - 1, x + w, y + h, COLOR_BORDER);
        Gui.func_73734_a(x, y, x + 1, y + h, COLOR_BORDER);
        Gui.func_73734_a(x + w - 1, y, x + w, y + h, COLOR_BORDER);

        int trackTop = y + (h - TRACK_H) / 2;
        float t = (value - min) / (float) Math.max(1, max - min);
        t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
        int knobX = trackLeft + Math.round(t * Math.max(1, trackRight - trackLeft));
        Gui.func_73734_a(trackLeft, trackTop, Math.max(trackLeft + 1, trackRight), trackTop + TRACK_H,
                COLOR_TRACK);
        Gui.func_73734_a(trackLeft, trackTop, Math.max(trackLeft + 1, knobX), trackTop + TRACK_H,
                COLOR_TRACK_DONE);
        Gui.func_73734_a(knobX - KNOB_W / 2, trackTop - 3, knobX + KNOB_W / 2, trackTop + TRACK_H + 3,
                COLOR_KNOB);

        // drawRect 会把当前颜色留成底下那个色，画字前先把 GL 状态摆回去
        GuiRender.beginGui();
        if (font != null) {
            int textY = y + (h - TEXT_H) / 2;
            font.func_175063_a(label, (float) (x + PAD), (float) textY, COLOR_TEXT);
            if (!withField) {
                String text = format(value);
                font.func_175063_a(text, (float) (x + w - PAD - font.func_78256_a(text)),
                        (float) textY, COLOR_TEXT);
            }
        }
        if (withField && field != null) {
            field.field_146209_f = fx;
            field.field_146210_g = fy;
            field.func_146194_f();
        }
        return hover || dragging;
    }

    /** 每 tick 调一次（输入框的光标闪烁）。 */
    public void update() {
        if (field != null) {
            field.func_146178_a();
        }
    }

    /**
     * 转发鼠标点击。
     *
     * @return true = 这次点击落在输入框里，调用方别再把它当按钮点击
     */
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (field == null) {
            return false;
        }
        if (inside(mouseX, mouseY, fieldX(), fieldY(), FIELD_W, FIELD_H)) {
            field.field_146209_f = fieldX();
            field.field_146210_g = fieldY();
            field.func_146192_a(mouseX, mouseY, mouseButton);
            return true;
        }
        if (field.func_146206_l()) {
            commit(); // 点到别处：先把正在编辑的数字提交掉
        }
        return false;
    }

    /**
     * 转发键盘。
     *
     * @return true = 输入框焦点在本行，这个按键已被吃掉
     */
    public boolean keyTyped(char typedChar, int keyCode) {
        if (field == null || !field.func_146206_l()) {
            return false;
        }
        if (keyCode == 1) { // Esc 放弃这次编辑，不关界面
            blur();
            refresh();
            return true;
        }
        if (keyCode == 28 || keyCode == 156) { // Enter / 小键盘 Enter
            commit();
            return true;
        }
        field.func_146201_a(typedChar, keyCode);
        return true;
    }

    public boolean isFocused() {
        return field != null && field.func_146206_l();
    }

    /** 外部（按钮、预设）改了值之后调，让框里的数字跟上。 */
    public void refresh() {
        if (field == null || field.func_146206_l()) {
            return;
        }
        String text = Integer.toString(sink.get());
        if (!text.equals(field.func_146179_b())) {
            field.func_146180_a(text);
        }
    }

    public void blur() {
        if (field != null) {
            field.func_146195_b(false);
        }
    }

    /**
     * 收尾：拖完松手、输入框回车、点到别处都走这里。
     *
     * <p>只有输入框**有焦点**时才解析框里的字——拖动滑轨时框里还是上一次的数字，
     * 一解析就会把刚拖出来的值顶掉（「松开滑块又变回去了」就是这么来的）。</p>
     */
    private void commit() {
        if (field != null && field.func_146206_l()) {
            String raw = field.func_146179_b().trim();
            int parsed;
            try {
                parsed = Integer.parseInt(raw);
            } catch (NumberFormatException notANumber) {
                parsed = sink.get(); // 框里不是合法整数：当作没改
            }
            if (parsed < min) {
                parsed = min;
            } else if (parsed > max) {
                parsed = max;
            }
            if (parsed != sink.get()) {
                sink.set(parsed);
            }
        }
        blur();
        refresh();
        if (onCommit != null) {
            onCommit.run();
        }
    }

    private void setFromMouse(int mouseX, int trackLeft, int trackRight) {
        int span = Math.max(1, trackRight - trackLeft);
        float t = (mouseX - trackLeft) / (float) span;
        t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
        int value = min + Math.round(t * (max - min));
        if (value != sink.get()) {
            sink.set(value);
            refresh(); // 拖的同时让右边的数字框/数值跟着走
        }
    }

    private int valueWidth(FontRenderer font, int value) {
        return font == null ? 0 : font.func_78256_a(format(value)) + PAD;
    }

    private String format(int value) {
        if (!unit.isEmpty()) {
            return value + unit;
        }
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private int fieldX() {
        return x + w - FIELD_W;
    }

    private int fieldY() {
        return y + (h - FIELD_H) / 2;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
