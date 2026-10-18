package meow.tooltipfix.client;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;

/**
 * 隐藏容器 GUI 的标题文字——但只在材质包确实覆盖了那个容器底图时才动手。
 *
 * <p>1.12.2 的容器界面会在底图上方用深灰 4210752 画两行字：顶部的容器名（合成/熔炉/箱子…）
 * 和底部的「物品栏」。现代版本（1.13+）已经不再画这两行，材质包也就按「没有这两行字」设计底图；
 * 于是 CozyUI 那种浅灰浮起面板上，这两行深灰小字几乎看不见，还压住了面板的圆角。</p>
 *
 * <p>判据是「覆盖」而不是「类名」：{@link LegacySheets#blit} 画到
 * {@code textures/gui/container/*.png} 且栈顶不是 {@code Default} 时，把当前 {@link GuiScreen}
 * 记进 {@link #COVERED}；{@link #hidden} 只对这张屏幕生效。所以原版底图 + 原版字体时行为完全不变。</p>
 *
 * <p>开关：设置界面里可以随时关（{@link TfSettings}），也可用 {@code -Dtooltipfix.hidetitles=false}
 * 作为启动默认值。</p>
 */
public final class TitleFilter {

    /** 开关：{@code -Dtooltipfix.hidetitles=false} 或设置界面（{@link TfSettings}）。 */
    private static boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty("tooltipfix.hidetitles"));

    /** 底图被材质包覆盖过的屏幕（用 identity 语义，换屏幕就是新实例）。 */
    private static final Set<GuiScreen> COVERED =
            Collections.newSetFromMap(new IdentityHashMap<GuiScreen, Boolean>());

    private static Field ySizeField;
    private static boolean ySizeTried;

    private TitleFilter() {
    }

    /** 当前是否隐藏容器标题（设置界面据此画开关）。 */
    public static boolean enabled() {
        return enabled;
    }

    /** 运行期开关：关掉时顺手清空「已覆盖」集合，下一帧立刻恢复画标题。 */
    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        if (!value) {
            reset();
        }
        Debug.info("hidetitles: " + (value ? "on" : "off") + " (settings)");
    }

    /** {@link LegacySheets#blit} 画到材质包覆盖的容器底图时调用。 */
    public static void noteCovered() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return;
        }
        GuiScreen screen = mc.field_71462_r;
        if (!(screen instanceof GuiContainer)) {
            return;
        }
        if (COVERED.size() > 64) {
            COVERED.clear(); // 兜底：别为走过的每个界面攒引用
        }
        if (COVERED.add(screen)) {
            Debug.once("hidetitles:" + screen.getClass().getName(),
                    "hide-titles: '" + screen.getClass().getSimpleName()
                            + "' 的容器底图来自材质包，隐藏标题文字");
        }
    }

    /**
     * @param text 即将用 {@code FontRenderer.func_78276_b(String,int,int,int)} 画出来的文字
     * @param x    GUI 坐标（未平移，相对容器左上角）
     * @param y    GUI 坐标
     * @return true = 这行字该吞掉（返回 0，不画）
     */
    public static boolean hidden(String text, int x, int y) {
        if (!enabled || text == null || text.isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return false;
        }
        GuiScreen screen = mc.field_71462_r;
        if (!(screen instanceof GuiContainer) || !COVERED.contains(screen)) {
            return false;
        }
        // 创造物品栏在 (8,6) 画的是**标签页名字**（GuiContainerCreative:283，颜色 0x404040），
        // 不是容器标题；1.21.2 也照画，藏掉就等于把标签页名字吃没了。搜索框文字走带阴影的
        // 5 参 drawString，本来就不经过这里。
        if (screen instanceof GuiContainerCreative) {
            return false;
        }
        // 1.12.2 所有容器标题都画在 y<=8：合成 (28,6)、玩家背包的「合成」(97,8)、熔炉/发射器/
        // 酿造台/村民居中 (_,6)、附魔 (12,5)、铁砧 (60,6)、箱子/潜影盒/马/创造物品栏 (8,6)。
        if (y >= 0 && y <= 8) {
            return true;
        }
        // 底部「物品栏」标签统一在 (8, ySize-94)，文本是玩家背包的显示名（酿造台/附魔台画的是
        // 方块自己的名字），所以按纵坐标认比按文本认更准。
        return y == ySize(screen) - 94;
    }

    public static void reset() {
        COVERED.clear();
    }

    /** {@code GuiContainer.field_147000_g}（protected int ySize），按类缓存反射结果。 */
    private static int ySize(GuiScreen screen) {
        if (!ySizeTried) {
            ySizeTried = true;
            ySizeField = findYSize();
        }
        if (ySizeField != null) {
            try {
                return ySizeField.getInt(screen);
            } catch (Throwable ignored) {
                // 反射失效就退回默认高度
            }
        }
        return 166;
    }

    private static Field findYSize() {
        Class<?> c = GuiContainer.class;
        String[] names = {"field_147000_g", "ySize"};
        for (String name : names) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return null;
    }
}
