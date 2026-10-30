package meow.tooltipfix.client;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 决定「物品悬浮框（tooltip）的外框由谁来画」。
 *
 * <p>ModernUI（modid {@code modernui}）自己接管 tooltip 渲染，和本模组的 sprite 版外框会打架：
 * 两边都往同一个位置画，最后只剩 ModernUI 的框。所以这里给一个开关，默认在检测到 ModernUI 时
 * 让出绘制权，由 ModernUI 画；需要本模组的框时把它改成 {@code tooltipfix}。
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
    /** ModernUI 的 modid；Fabric / Forge / NeoForge 三端一致。 */
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
            "# 关于 ModernUI：",
            "#   ModernUI 会在 RenderTooltipEvent.Pre 上自己画框，再在 LOW 优先级把事件取消，",
            "#   原版路径（也就是 TooltipFix 的注入点）会被短路，于是只剩 ModernUI 的框。",
            "#   所以选 tooltipfix 时，本模组会把 ModernUI 的 TooltipRenderer.sTooltip 置为 false，",
            "#   让原版路径跑起来再由本模组接管；切回 auto / vanilla 时再还原为 true。",
            "#",
            "# 日志里可以看到实际生效的结果（latest.log 里搜 tooltip box）：",
            "#   tooltip box: mode=tooltipfix modernui=true -> tooltipfix",
            "",
            KEY + AUTO,
            "");

    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-tooltip");

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

    /**
     * 每帧/每屏调一次：即使本模组的 tooltip 注入点被别的模组（ModernUI）短路掉，
     * 也要先把 ModernUI 的开关同步好，否则「切回本模组」永远不会生效。
     */
    public static void tick() {
        takeOver();
    }

    /**
     * ModernUI 在 {@code RenderTooltipEvent.Pre} 上用自己的渲染器画框，并在 LOW 优先级把事件取消，
     * 于是原版路径（也就是本模组的注入点）根本不会执行——这就是「只显示现代化 UI 的提示框」的原因。
     * 它留了一个公开开关 {@code icyllis.modernui.mc.TooltipRenderer.sTooltip}：关掉之后它既不画也不取消。
     * 所以本模组要接管时（mode=tooltipfix）必须把它拧到 false，让出绘制权时再还原。
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
            // ModernUI 可能不在，或者改了字段名：静默放弃，退回「谁先画谁赢」的行为。
        }
        modernUiFlagBroken = true;
        return null;
    }

    /** 运行环境里是否装了 ModernUI（反射探测，不引入编译期依赖）。 */
    public static boolean modernUiPresent() {
        if (modernUi != null) {
            return modernUi;
        }
        boolean found = viaFabric() || viaForge() || viaClasses();
        modernUi = found;
        return found;
    }

    private static boolean viaFabric() {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object value = loader.getMethod("isModLoaded", String.class).invoke(instance, MODERN_UI_ID);
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean viaForge() {
        try {
            Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            Object value = modList.getMethod("isLoaded", String.class).invoke(instance, MODERN_UI_ID);
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean viaClasses() {
        for (String name : new String[] {
                "icyllis.modernui.forge.ModernUIForge",
                "icyllis.modernui.fabric.ModernUIFabric",
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
        // modernui 只是给用户一个更直观的写法，语义等同 auto（装了就让给 ModernUI）。
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
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) {
                return null;
            }
            return new File(new File(mc.gameDirectory, "config"), FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
