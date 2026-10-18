package meow.tooltipfix.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * 玩家的 TooltipFix 设置：字体兼容引擎开关、字号倍率、隐藏容器标题、CozyUI 预设。
 *
 * <p>文件是 {@code config/tooltipfix-hdfont.txt}——沿用上一版只存字号的同一个文件，写回时把缺的
 * 键补齐，旧文件里的 {@code scale=} 照旧读得出来。系统属性仍然可作「启动默认值」：
 * {@code -Dtooltipfix.hdfont=false}、{@code -Dtooltipfix.hidetitles=false}、
 * {@code -Dtooltipfix.hdfont.scale=N}；文件里明确写下的键优先。</p>
 *
 * <p><b>CozyUI 预设的语义：</b>用户要的是「一键把 mod 调成 CozyUI 能用的样子」，也就是
 * 字体引擎开 + 字号 100% + 隐藏容器标题。打开时先记下当前三项，关掉时原样还原——这样它是个
 * 真正的开关，而不是单向的「应用」按钮。</p>
 *
 * <p><b>为什么状态都在这里：</b>写同一个文件的类必须只有一个。{@link HdFont} 的
 * {@code userScale()/saveUserScale()} 都转发到本类，别处再各自读写会出现「滑块拖动把
 * {@code font=} 那一行写掉」这种事故。</p>
 */
public final class TfSettings {

    /** 配置文件名（历史原因带 hdfont，现在存的是全部设置）。 */
    private static final String FILE_NAME = "tooltipfix-hdfont.txt";

    /** 旧版字号覆盖属性。 */
    private static final String SCALE_PROPERTY = "tooltipfix.hdfont.scale";

    /** 字号倍率的默认值与可调范围（与 {@link NumberSlider} 里字号那行的两端一致）。 */
    static final float DEFAULT_SCALE = 1.0F;
    static final float MIN_SCALE = 0.5F;
    static final float MAX_SCALE = 2.0F;

    /** 预设打开时字号固定 100% 的判定容差。 */
    private static final float SCALE_EPSILON = 0.005F;

    /**
     * 字形偏移的可调范围（逻辑像素，整数步进）。
     *
     * <p>为什么要这个：材质包里的 ttf 跟原版 unifont 混排时（拉丁走 ttf、中文回退原版），
     * 两边的字高和基线常常对不齐，看着「高一块低一块」。整数步进是为了保持像素对齐——
     * 半像素偏移会让 GL_LINEAR 把笔画糊成两像素宽。</p>
     */
    static final int MIN_OFFSET = -8;
    static final int MAX_OFFSET = 8;

    /**
     * 字间距的可调范围（逻辑像素，整数，加在每个字形的前进宽度上）。
     *
     * <p>有的材质包字体自带很宽/很窄的 side bearing，跟原版中文混排时疏密不一致。
     * 这是「每个字之后再加多少」：负值收紧、正值放松。</p>
     */
    static final int MIN_SPACING = -4;
    static final int MAX_SPACING = 8;

    private static boolean loaded;
    /** 正在读文件：读的过程中任何 getter 都不许再触发一次 {@link #load()}。 */
    private static boolean loading;

    private static boolean fontEngine = true;
    private static boolean hideTitles = true;
    private static boolean preset;
    private static float scale = DEFAULT_SCALE;
    private static int offsetX;
    private static int offsetY;
    private static int spacing;

    /** 打开预设前的原值，关预设时还原。 */
    private static boolean backupFont = true;
    private static boolean backupTitles = true;
    private static float backupScale = DEFAULT_SCALE;
    private static int backupOffsetX;
    private static int backupOffsetY;
    private static int backupSpacing;

    /**
     * 按材质包分开存的字形微调：包名 → {@code {offsetX, offsetY, spacing}}。
     *
     * <p>为什么分开：偏移是为了「让某个字体包的字形跟原版中文对齐」，不同包的基线、
     * side bearing 完全不同，同一组值在 A 包对得齐、换到 B 包就偏一半。所以每个包记自己那份，
     * 换包等于换一组微调，用户不用每次重调。</p>
     */
    private static final Map<String, int[]> packs = new LinkedHashMap<String, int[]>();

    /** 当前生效的桶名（{@code ""} = 原版字体）；由 {@link #syncPack()} 在资源重载后切换。 */
    private static String packKey = "";

    /** 老文件里的全局 {@code offset.x/offset.y/spacing} 是否已经迁进第一个识别出的包。 */
    private static boolean migrated;


    private TfSettings() {
    }

    // ------------------------------------------------------------------ 读取

    /** 读一次配置并应用（幂等；读写都从这里进来）。 */
    public static void load() {
        if (loaded || loading) {
            return;
        }
        loading = true;
        try {
            // 系统属性只当默认值：文件里写了就以文件为准
            fontEngine = !"false".equalsIgnoreCase(System.getProperty("tooltipfix.hdfont"));
            hideTitles = !"false".equalsIgnoreCase(System.getProperty("tooltipfix.hidetitles"));
            scale = defaultScale();
            offsetX = 0;
            offsetY = 0;
            spacing = 0;
            backupFont = fontEngine;
            backupTitles = hideTitles;
            backupScale = scale;
            backupOffsetX = offsetX;
            backupOffsetY = offsetY;
            backupSpacing = spacing;
            readFile();
            migrated = !packs.isEmpty();
        } finally {
            loading = false;
        }
        loaded = true;
        apply();
    }

    // ------------------------------------------------------------------ 查询

    public static boolean fontEngine() {
        load();
        return fontEngine;
    }

    public static boolean hideTitles() {
        load();
        return hideTitles;
    }

    public static boolean preset() {
        load();
        return preset;
    }

    /** 字号倍率：1.0 = 用材质包自己声明的 size（低于下限时由 HdFont 托底）。 */
    public static float scale() {
        load();
        return scale;
    }

    /** 字形水平偏移（逻辑像素，正 = 往右）。 */
    public static int offsetX() {
        load();
        return offsetX;
    }

    /** 字形垂直偏移（逻辑像素，正 = 往下）。 */
    public static int offsetY() {
        load();
        return offsetY;
    }

    /** 字间距（逻辑像素，加在每个字形前进宽度上，正 = 更松）。 */
    public static int spacing() {
        load();
        return spacing;
    }

    /** 当前偏移属于哪个材质包（原始包名；{@code ""} = 原版字体）。 */
    public static String packKey() {
        load();
        return packKey;
    }

    /** 给界面显示用的包名：去掉 {@code §} 颜色码，太长就截断（包名来自 zip 文件名，可能很长）。 */
    public static String packLabel() {
        String key = packKey();
        if (key.length() == 0) {
            return "原版字体";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\u00a7') {
                i++; // 跳过格式码，否则界面上的包名会变成彩色/乱码
                continue;
            }
            sb.append(c);
        }
        String text = sb.toString().trim();
        if (text.length() > 18) {
            text = text.substring(0, 17) + "…";
        }
        return text;
    }

    /**
     * 资源重载后调用：把当前微调存回旧桶，换上新包自己那份。
     *
     * <p>顺序很重要——必须先 {@link HdFont#reload()} 让它解析出新包名，再调这里。</p>
     */
    public static void syncPack() {
        load();
        String key = HdFont.fontPack();
        if (key == null) {
            key = "";
        }
        if (key.equals(packKey)) {
            return;
        }
        String previous = packKey;
        int[] current = new int[]{offsetX, offsetY, spacing};
        // 首次从「老配置的全局值」切到具体包时不要把它记成原版字体那份，直接迁过去
        if (!(previous.length() == 0 && !migrated)) {
            packs.put(previous, current);
        }
        packKey = key;
        int[] stored = packs.get(key);
        if (stored == null && previous.length() == 0 && !migrated
                && (current[0] != 0 || current[1] != 0 || current[2] != 0)) {
            // 升级路径：老文件里的全局 offset/ spacing 当作「当前这个包」的，观感不变
            stored = current.clone();
            packs.put(key, stored);
        }
        migrated = true;
        if (stored == null) {
            stored = new int[]{0, 0, 0};
        }
        offsetX = stored[0];
        offsetY = stored[1];
        spacing = stored[2];
        Debug.info("settings: pack='" + packKey + "' offset=" + offsetX + "," + offsetY
                + " spacing=" + spacing + " (known packs=" + packs.size() + ")");
    }

    // ------------------------------------------------------------------ 修改

    public static void setFontEngine(boolean value) {
        load();
        if (fontEngine == value) {
            return;
        }
        fontEngine = value;
        preset = false; // 手动单项调过之后「预设」这个说法就不成立了
        apply();
        save();
    }

    public static void setHideTitles(boolean value) {
        load();
        if (hideTitles == value) {
            return;
        }
        hideTitles = value;
        preset = false;
        apply();
        save();
    }

    /**
     * 改字号（只落内存，写盘交给 {@link #save()}）。
     *
     * <p>滑块拖动时每帧都会走到这里，所以这里不写文件；松手时由
     * {@link HdFont#saveUserScale()} 落盘一次。</p>
     */
    public static void setScale(float value) {
        load();
        float next = clampScale(value);
        if (Math.abs(next - scale) < SCALE_EPSILON) {
            return;
        }
        scale = next;
        if (Math.abs(next - DEFAULT_SCALE) >= SCALE_EPSILON) {
            preset = false; // 预设 = 100%，改走了就不再是预设
        }
    }

    /**
     * 改字形偏移（只落内存，写盘交给 {@link #save()}）。
     *
     * <p>同 {@link #setScale}：拖动时每帧调用，不写文件。偏离 (0,0) 就不再算预设。</p>
     */
    public static void setOffset(int x, int y) {
        load();
        int nx = clampOffset(x);
        int ny = clampOffset(y);
        if (nx == offsetX && ny == offsetY) {
            return;
        }
        offsetX = nx;
        offsetY = ny;
        storeCurrent();
        if (nx != 0 || ny != 0) {
            preset = false;
        }
    }

    /** 改字间距（只落内存，写盘交给 {@link #save()}）。 */
    public static void setSpacing(int value) {
        load();
        int next = clampSpacing(value);
        if (next == spacing) {
            return;
        }
        spacing = next;
        storeCurrent();
        if (next != 0) {
            preset = false;
        }
    }

    /**
     * 打开/关闭 CozyUI 预设。
     *
     * <p>开 = 字体引擎开 + 字号 100% + 隐藏容器标题 + 偏移归零；关 = 还原打开前各项的原值。</p>
     */
    public static void setPreset(boolean on) {
        load();
        if (preset == on) {
            return;
        }
        if (on) {
            backupFont = fontEngine;
            backupTitles = hideTitles;
            backupScale = scale;
            backupOffsetX = offsetX;
            backupOffsetY = offsetY;
            backupSpacing = spacing;
            fontEngine = true;
            hideTitles = true;
            scale = DEFAULT_SCALE;
            offsetX = 0;
            offsetY = 0;
            spacing = 0;
            preset = true;
        } else {
            fontEngine = backupFont;
            hideTitles = backupTitles;
            scale = backupScale;
            offsetX = backupOffsetX;
            offsetY = backupOffsetY;
            spacing = backupSpacing;
            preset = false;
        }
        storeCurrent();
        apply();
        save();
    }

    /**
     * 一键把**全部**配置恢复成默认：引擎开、隐藏标题开、字号 100%、偏移 (0,0)、字间距 0。
     *
     * <p>不走 {@link #setPreset(boolean)} 的还原路径——预设关掉是「回到开预设之前」，
     * 而这里要的是「回到刚装模组时的样子」。</p>
     */
    public static void resetAll() {
        load();
        fontEngine = true;
        hideTitles = true;
        scale = DEFAULT_SCALE;
        offsetX = 0;
        offsetY = 0;
        spacing = 0;
        preset = false;
        backupFont = true;
        backupTitles = true;
        backupScale = DEFAULT_SCALE;
        backupOffsetX = 0;
        backupOffsetY = 0;
        backupSpacing = 0;
        packs.clear(); // 「全部」恢复默认：所有包各自的微调一起清掉
        apply();
        save();
    }

    /** 把当前设置写回 config，写失败只记一行日志（不影响游戏）。 */
    public static void save() {
        File file = configFile();
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Debug.info("settings: cannot create " + parent);
            return;
        }
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write("# TooltipFix 设置（游戏内：资源包界面左下角「TooltipFix 设置」）\n");
            writer.write("# font   = 字体兼容引擎：on 读材质包 1.13+ 的字体定义，off 用 1.12.2 原生 8px\n");
            writer.write("# scale  = 字号倍率：1.00 是材质包自己的 size（低于 6.5px 会先抬到 6.5），0.50 ~ 2.00\n");
            writer.write("# titles = 隐藏容器标题：仅当材质包覆盖了该容器底图时才生效\n");
            writer.write("# offset = 字形偏移（逻辑像素，整数，-8 ~ 8）：材质包 ttf 与中文混排对不齐时用\n");
            writer.write("# spacing= 字间距（逻辑像素，整数，-4 ~ 8）：加在每个字的前进宽度上\n");
            writer.write("# 上面两项按材质包分开存，见文件末尾的 pack.<包名>.offset.x / .offset.y / .spacing\n");
            writer.write("# preset = CozyUI 预设：开 = font 开 + scale 1.00 + titles 开 + offset 0 + spacing 0\n");
            writer.write("font=" + onOff(fontEngine) + "\n");
            writer.write("scale=" + String.format(Locale.US, "%.2f", scale) + "\n");
            writer.write("titles=" + onOff(hideTitles) + "\n");
            writer.write("preset=" + onOff(preset) + "\n");
            // 预设的原值也存下来：下次开游戏时把预设关掉仍然能还原
            writer.write("preset.font=" + onOff(backupFont) + "\n");
            writer.write("preset.scale=" + String.format(Locale.US, "%.2f", backupScale) + "\n");
            writer.write("preset.titles=" + onOff(backupTitles) + "\n");
            writer.write("preset.offset.x=" + backupOffsetX + "\n");
            writer.write("preset.offset.y=" + backupOffsetY + "\n");
            writer.write("preset.spacing=" + backupSpacing + "\n");
            // 每个材质包自己那份字形微调（键名里的包名原样保留，不做大小写转换）
            if (packKey.length() > 0) {
                packs.put(packKey, new int[]{offsetX, offsetY, spacing});
            }
            for (Map.Entry<String, int[]> entry : packs.entrySet()) {
                String name = entry.getKey();
                if (name.length() == 0) {
                    continue;
                }
                int[] value = entry.getValue();
                writer.write("pack." + name + ".offset.x=" + value[0] + "\n");
                writer.write("pack." + name + ".offset.y=" + value[1] + "\n");
                writer.write("pack." + name + ".spacing=" + value[2] + "\n");
            }
        } catch (Throwable t) {
            Debug.info("settings: cannot write " + file + " " + t);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                    // 关不掉就算了，下次覆盖写
                }
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 把内存里的当前值同步进当前包的桶（只改 map，不写文件）。 */
    private static void storeCurrent() {
        packs.put(packKey, new int[]{offsetX, offsetY, spacing});
    }

    /** 把设置落实到引擎。字体引擎是重启级别的（要重建字形缓存），隐藏标题是当帧生效。 */
    private static void apply() {
        HdFont.setEnabled(fontEngine);
        TitleFilter.setEnabled(hideTitles);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    /** 系统属性 {@code -Dtooltipfix.hdfont.scale=N} 优先于默认值，但低于文件里的 {@code scale=}。 */
    private static float defaultScale() {
        String prop = System.getProperty(SCALE_PROPERTY);
        if (prop != null) {
            try {
                float value = Float.parseFloat(prop.trim());
                if (value > 0.0F) {
                    return clampScale(value);
                }
            } catch (NumberFormatException ignored) {
                // 属性写坏了就当没写
            }
        }
        return DEFAULT_SCALE;
    }

    static float clampScale(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT_SCALE;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    static int clampOffset(int value) {
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }

    static int clampSpacing(int value) {
        return Math.max(MIN_SPACING, Math.min(MAX_SPACING, value));
    }

    private static void readFile() {
        File file = configFile();
        if (file == null || !file.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String rawKey = line.substring(0, eq).trim();
                String key = rawKey.toLowerCase(Locale.US);
                String raw = line.substring(eq + 1).trim();
                if (rawKey.length() > 5 && rawKey.substring(0, 5).equalsIgnoreCase("pack.")) {
                    readPack(rawKey.substring(5), raw);
                } else if ("font".equals(key)) {
                    fontEngine = parseBool(raw, fontEngine);
                } else if ("titles".equals(key)) {
                    hideTitles = parseBool(raw, hideTitles);
                } else if ("preset".equals(key)) {
                    preset = parseBool(raw, preset);
                } else if ("scale".equals(key)) {
                    scale = parseScale(raw, scale);
                } else if ("offset.x".equals(key)) {
                    offsetX = parseInt(raw, offsetX, MIN_OFFSET, MAX_OFFSET);
                } else if ("offset.y".equals(key)) {
                    offsetY = parseInt(raw, offsetY, MIN_OFFSET, MAX_OFFSET);
                } else if ("spacing".equals(key)) {
                    spacing = parseInt(raw, spacing, MIN_SPACING, MAX_SPACING);
                } else if ("preset.font".equals(key)) {
                    backupFont = parseBool(raw, backupFont);
                } else if ("preset.titles".equals(key)) {
                    backupTitles = parseBool(raw, backupTitles);
                } else if ("preset.scale".equals(key)) {
                    backupScale = parseScale(raw, backupScale);
                } else if ("preset.offset.x".equals(key)) {
                    backupOffsetX = parseInt(raw, backupOffsetX, MIN_OFFSET, MAX_OFFSET);
                } else if ("preset.offset.y".equals(key)) {
                    backupOffsetY = parseInt(raw, backupOffsetY, MIN_OFFSET, MAX_OFFSET);
                } else if ("preset.spacing".equals(key)) {
                    backupSpacing = parseInt(raw, backupSpacing, MIN_SPACING, MAX_SPACING);
                }
            }
        } catch (Throwable t) {
            Debug.info("settings: cannot read " + file + " " + t);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                    // 读坏了就用默认值
                }
            }
        }
    }

    /**
     * 读 {@code pack.<包名>.offset.x / .offset.y / .spacing}。
     *
     * <p>包名原样保留（可能含中文、空格、{@code §}），只有后缀按小写比较。</p>
     */
    private static void readPack(String tail, String raw) {
        String lower = tail.toLowerCase(Locale.US);
        int kind;
        String suffix;
        if (lower.endsWith(".offset.x")) {
            kind = 0;
            suffix = ".offset.x";
        } else if (lower.endsWith(".offset.y")) {
            kind = 1;
            suffix = ".offset.y";
        } else if (lower.endsWith(".spacing")) {
            kind = 2;
            suffix = ".spacing";
        } else {
            return;
        }
        String name = tail.substring(0, tail.length() - suffix.length());
        if (name.length() == 0) {
            return;
        }
        int[] value = packs.get(name);
        if (value == null) {
            value = new int[3];
            packs.put(name, value);
        }
        if (kind == 2) {
            value[2] = parseInt(raw, value[2], MIN_SPACING, MAX_SPACING);
        } else {
            value[kind] = parseInt(raw, value[kind], MIN_OFFSET, MAX_OFFSET);
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if ("on".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw) || "1".equals(raw)) {
            return true;
        }
        if ("off".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw) || "0".equals(raw)) {
            return false;
        }
        return fallback;
    }

    private static float parseScale(String raw, float fallback) {
        try {
            float value = Float.parseFloat(raw);
            return value > 0.0F ? clampScale(value) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code config/tooltipfix-hdfont.txt}；拿不到游戏目录就退回当前工作目录。 */
    private static File configFile() {
        File dir = null;
        try {
            Minecraft mc = Minecraft.func_71410_x();
            if (mc != null) {
                dir = mc.field_71412_D;
            }
        } catch (Throwable ignored) {
            // 早于游戏初始化时调用，退回相对路径
        }
        if (dir == null) {
            dir = new File(".");
        }
        return new File(new File(dir, "config"), FILE_NAME);
    }
}
