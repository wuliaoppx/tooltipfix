package meow.tooltipfix.client;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import net.minecraft.client.Minecraft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 决定「物品悬浮框（tooltip）的外框由谁来画」。
 *
 * <p>1.20.1 / 1.21.1 变体里同一个类负责和 ModernUI 协调；1.12.2 这边没有 ModernUI 可用，
 * 但配置语义保持一致：默认 {@code auto}，装了别的接管 tooltip 的模组就让给它，否则用本模组。
 *
 * <p>1.12.2 的特殊之处：{@code GuiScreen.func_146283_a} 的方法体已经被 coremod 整个换掉，
 * 所以「让给别人」只能是「本模组按原版样式画一遍」（{@link TooltipSprites#drawFallback}），
 * 不可能真的把控制权交回去。
 *
 * <p>配置文件：{@code config/tooltipfix-tooltip.txt}。文件里带完整注释，实际生效的是
 * {@code value=} 那一行；保存即生效（250ms 轮询），不用重启游戏。
 */
public final class TooltipMode {
    /** 配置文件里的三个取值。 */
    public static final String AUTO = "auto";
    public static final String SELF = "tooltipfix";
    public static final String OTHER = "vanilla";

    private static final String FILE_NAME = "tooltipfix-tooltip.txt";
    private static final String KEY = "value=";
    /** ModernUI 的 modid；1.12.2 端至今没有发布，留着是为了配置跨版本一致。 */
    private static final String MODERN_UI_ID = "modernui";
    /** ModernUI 自己那个「画不画 tooltip」的公开静态开关。 */
    private static final String MODERN_UI_RENDERER = "icyllis.modernui.mc.TooltipRenderer";
    private static final String MODERN_UI_FLAG = "sTooltip";
    /** 检测间隔：每 250ms 最多 stat 一次配置文件。 */
    private static final long RECHECK_MS = 250L;

    /** 首次生成 / 补注释时写进文件的内容。{@code value=auto} 会被替换成当前取值。 */
    private static final String TEMPLATE = String.join(System.lineSeparator(),
            "# ============================================================",
            "# TooltipFix —— 物品悬浮框（tooltip）的外框由谁绘制",
            "# ============================================================",
            "#",
            "# 只需要改下面 value= 那一行，保存后 0.25 秒内生效，不用重启游戏。",
            "#",
            "#   auto        装了 ModernUI 就让给它，否则用 TooltipFix（默认）",
            "#   tooltipfix  始终用 TooltipFix 的材质包外框",
            "#   vanilla     始终不接管，保留原版或其它模组画的外框",
            "#",
            "# 注意：1.12.2 没有 ModernUI，所以这里实际只有 auto / tooltipfix 两档有区别——",
            "#       auto 与 vanilla 都会画成原版渐变框。",
            "#",
            "# 日志里可以看到实际生效的结果（latest.log 里搜 tooltip box）：",
            "#   tooltip box: mode=auto modernui=false -> tooltipfix",
            "",
            KEY + AUTO,
            "");

    private static final Logger LOG = LogManager.getLogger("tooltipfix-tooltip");

    private static String mode;
    private static long stamp = Long.MIN_VALUE;
    private static long checkedAt;
    private static Boolean modernUi;
    private static String reported;

    /** 我们是否已经把 ModernUI 的 tooltip 渲染关掉了（只有确实改成功过才是 true）。 */
    private static boolean suppressed;
    private static Field modernUiFlag;
    private static boolean modernUiFlagBroken;

    private TooltipMode() {
    }

    /** 本模组是否应该接管 tooltip 外框。每次渲染都会问，内部有缓存。 */
    public static boolean takeOver() {
        String m = mode();
        boolean modern = modernUiPresent();
        boolean take;
        if (SELF.equals(m)) {
            take = true;
        } else if (OTHER.equals(m)) {
            take = false;
        } else {
            take = !modern;
        }
        syncModernUi(take, modern);
        report(m, modern, take);
        return take;
    }

    /** 每 tick 调一次：即使 tooltip 注入点被别的模组短路掉，也要把配置读起来。 */
    public static void tick() {
        takeOver();
    }

    /**
     * ModernUI 在 {@code RenderTooltipEvent.Pre} 上自己画框再取消事件，本模组的注入点会失效。
     * 它留了公开开关 {@code TooltipRenderer.sTooltip}，要接管时拧到 false，让出时还原。
     */
    private static void syncModernUi(boolean take, boolean modern) {
        if (!modern || modernUiFlagBroken || take == suppressed) {
            return;
        }
        Field field = modernUiTooltipField();
        if (field == null) {
            return;
        }
        try {
            field.setBoolean(null, !take);
            suppressed = take;
            LOG.info("tooltip box: modernui {}.{}={}", "TooltipRenderer", MODERN_UI_FLAG, !take);
        } catch (Throwable t) {
            modernUiFlagBroken = true;
            LOG.warn("tooltip box: cannot toggle ModernUI tooltip renderer", t);
        }
    }

    private static Field modernUiTooltipField() {
        if (modernUiFlag != null) {
            return modernUiFlag;
        }
        try {
            Field f = Class.forName(MODERN_UI_RENDERER).getField(MODERN_UI_FLAG);
            if (f.getType() == boolean.class) {
                modernUiFlag = f;
                return f;
            }
        } catch (Throwable ignored) {
            // ModernUI 不在，或者改了字段名：静默放弃。
        }
        modernUiFlagBroken = true;
        return null;
    }

    /** 运行环境里是否装了 ModernUI（反射探测，不引入编译期依赖）。 */
    public static boolean modernUiPresent() {
        if (modernUi != null) {
            return modernUi;
        }
        boolean found = viaForge() || viaClasses();
        modernUi = found;
        return found;
    }

    private static boolean viaForge() {
        try {
            Class<?> loader = Class.forName("net.minecraftforge.fml.common.Loader");
            Object value = loader.getMethod("isModLoaded", String.class).invoke(null, MODERN_UI_ID);
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean viaClasses() {
        for (String name : new String[] {
                "icyllis.modernui.forge.ModernUIForge",
                "icyllis.modernui.ModernUI"}) {
            try {
                Class.forName(name);
                return true;
            } catch (Throwable ignored) {
                // 继续试下一个
            }
        }
        return false;
    }

    private static void report(String m, boolean modern, boolean take) {
        String state = m + '|' + modern + '|' + take;
        if (state.equals(reported)) {
            return;
        }
        reported = state;
        if (take) {
            LOG.info("tooltip box: mode={} modernui={} -> tooltipfix", m, modern);
        } else if (modern) {
            LOG.info("tooltip box: mode={} modernui={} -> modernui", m, modern);
        } else {
            LOG.info("tooltip box: mode={} modernui={} -> vanilla", m, modern);
        }
    }

    private static String mode() {
        long now = System.currentTimeMillis();
        if (mode == null || now - checkedAt >= RECHECK_MS) {
            checkedAt = now;
            readConfig();
        }
        return mode == null ? AUTO : mode;
    }

    private static void readConfig() {
        File file = configFile();
        if (file == null) {
            mode = AUTO;
            return;
        }
        long current = file.exists() ? (file.lastModified() * 31L + file.length()) : -1L;
        if (current == stamp) {
            return;
        }
        stamp = current;
        if (!file.exists()) {
            mode = AUTO;
            write(file, AUTO);
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            mode = AUTO;
            return;
        }
        String parsed = parse(text);
        mode = parsed;
        // 老版本留下的是「光秃秃一行 auto」：补上注释模板，保留用户当前的取值。
        if (!text.contains("#") || !text.contains(KEY)) {
            write(file, parsed);
        }
    }

    /** 兼容三种写法：带注释的 {@code value=auto}、旧的单行 {@code auto}、带行内注释的 {@code auto # ...}。 */
    private static String parse(String text) {
        String fallback = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase("value")) {
                return normalize(line.substring(eq + 1));
            }
            if (fallback == null) {
                fallback = line;
            }
        }
        return normalize(fallback == null ? AUTO : fallback);
    }

    private static String normalize(String text) {
        String value = text.trim().toLowerCase();
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash).trim();
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (SELF.equals(value)) {
            return SELF;
        }
        if (OTHER.equals(value) || "off".equals(value) || "none".equals(value)) {
            return OTHER;
        }
        return AUTO;
    }

    private static void write(File file, String value) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            String text = TEMPLATE.replace(KEY + AUTO, KEY + value);
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            stamp = file.lastModified() * 31L + file.length();
        } catch (IOException ignored) {
            // 写不了就只用内存里的默认值
        }
    }

    private static File configFile() {
        try {
            Minecraft mc = Minecraft.func_71410_x();
            if (mc == null || mc.field_71412_D == null) {
                return null;
            }
            return new File(new File(mc.field_71412_D, "config"), FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
