package meow.tooltipfix.client;

import com.google.common.io.ByteStreams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * HD 字体引擎：直接读材质包 1.13+ 的 {@code assets/minecraft/font/default.json}，
 * 按 provider 的 height/ascent 以原生分辨率绘制字形，绕开 1.12.2 的 8px ascii 网格
 * 与 16 texel 的 unicode 页格子。
 *
 * <p>调用点由 {@code FontRendererTransformer} 注入到 FontRenderer 的两个私有/公开方法里：
 * 有 HD 字体时走这里，没有时原版代码照旧执行（不打补丁的路径完全不经过本类）。</p>
 */
public final class HdFont {

    /** 关掉 HD 字体引擎（退回 1.12.2 原生 8px 管线）。 */
    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("tooltipfix.hdfont"));

    /** 1.12.2 里 posY 到基线的距离（ascii 格子 8 行，'A' 占 0..6、'g' 到底 7）。 */
    private static final float BASELINE = 7.0F;

    /** 字形图集的取样倍率（相对逻辑像素）。4 = 用材质包原生分辨率。 */
    private static final int DENSITY = Math.max(1, Integer.getInteger("tooltipfix.hdfont.density", 4));

    /** 原版对空格硬编码 4 像素，这里保持一致（材质包的空格格子里没有墨，算出来会是 1）。 */
    private static final int SPACE_ADVANCE = 4;

    /** ttf 字号的「可读性下限」：小于这个逻辑字号就抬上来（原版 ascii 是 8px 高）。 */
    private static final float TTF_MIN_EM = 8.0F;

    /** ttf 放大倍率上限，防止异常 json 把字放到撑破界面。 */
    private static final float TTF_MAX_SCALE = 4.0F;

    private static final Glyph[] GLYPHS = new Glyph[65536];

    /** 懒 provider 试过但字体里没有这个字形（避免每次都重新栅格化一次）。 */
    private static final boolean[] MISSING = new boolean[65536];

    /**
     * 懒 provider：{@code "type":"ttf"} 只声明字体文件与字号，没有任何字符表，
     * 所以字形必须在第一次用到时现栅格化（见 {@link Ttf}）。
     */
    private static Ttf TTF;

    /** 原版 ascii 字形图；材质包/字体包把它覆盖成 2x、4x 时由 {@link #legacyAscii} 接管。 */
    private static final ResourceLocation LEGACY_ASCII =
            new ResourceLocation("minecraft", "textures/font/ascii.png");

    private static boolean dirty = true;
    private static boolean available;
    private static Provider[] providers = new Provider[0];
    private static int glyphCount;

    /** 所有 provider 里最大的字形绘制高度（GUI px）。1.12.2 的行距写死 10，得靠它补差。 */
    private static int lineHeight = 9;

    /** 诊断计数器：{@link #advance(char)} 被调用的次数。 */
    private static int CALLS;
    /** 重载时要回收的旧贴图（GL 删除必须回到客户端线程做）。 */
    private static final List<Integer> PENDING_DELETE = new ArrayList<Integer>();

    private HdFont() {
    }

    // ---------------------------------------------------------------- 查询

    /** 极热路径（每个字形一次），只读一个静态布尔。 */
    public static boolean available() {
        return available;
    }

    /**
     * 这个字符有没有 HD 字形可画。
     *
     * <p>transformer 用它决定「走 HD 引擎还是走原版方法」：没有字形的字符（比如只装了
     * {@code ascii.png} 的字体包、字符串里的中文）必须交回原版管线，否则会整片消失。</p>
     */
    public static boolean has(char c) {
        if (c == ' ') {
            return true;
        }
        if (GLYPHS[c] != null) {
            return true;
        }
        return lazy(c) != null;
    }

    /** 整串都能用 HD 字形画吗？{@code stringWidth} 用它决定要不要整串退回原版（保证宽度一致）。 */
    public static boolean covers(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') {
                i++; // 颜色码后面的字符不参与绘制
                continue;
            }
            if (!has(c)) {
                return false;
            }
        }
        return true;
    }

    /** 懒 provider 取字形（会栅格化并缓存）；没有则 null。 */
    private static Glyph lazy(char c) {
        Ttf t = TTF;
        if (t == null || MISSING[c]) {
            return null;
        }
        Glyph g = t.glyph(c);
        if (g == null) {
            MISSING[c] = true;
            return null;
        }
        GLYPHS[c] = g;
        return g;
    }

    /**
     * HD 字形实际占的高度（GUI px）。
     *
     * <p>1.12.2 把 {@code FONT_HEIGHT} 写死成 9、行距写死成 10，而材质包主字体（mcsans）
     * 的 {@code height=12 / ascent=9}，所以字形会从 {@code y-2} 画到 {@code y+10}：不改行距的话，
     * 上一行的降部和下一行的升部会叠在一起，tooltip/聊天栏看起来就是「挤成一团」。
     */
    public static int lineHeight() {
        return available ? lineHeight : 9;
    }

    /** 行距（GUI px）：原版 10；HD 下取字形高度 +1，留 1px 呼吸。 */
    public static int lineStep() {
        return available ? lineHeight + 1 : 10;
    }

    /** 每 tick 兜底一次：资源包重载时机不对（或首次 reload 时 Minecraft 还没建好）时补上解析。 */
    public static void tick() {
        if (!PENDING_DELETE.isEmpty()) {
            int[] ids = new int[PENDING_DELETE.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = PENDING_DELETE.get(i);
            }
            PENDING_DELETE.clear();
            try {
                for (int id : ids) {
                    GL11.glDeleteTextures(id);
                }
            } catch (Throwable ignored) {
                // 上下文没了就随它去，反正进程要退出了
            }
        }
        if (dirty) {
            ensure();
        }
    }

    /** 前进宽度，语义同 FontRenderer#getCharWidth：'§' 返回 -1。 */
    public static int advance(char c) {
        CALLS++;
        if (c == '\u00a7') {
            return -1;
        }
        if (c == ' ') {
            return SPACE_ADVANCE;
        }
        Glyph g = GLYPHS[c];
        if (g == null) {
            g = lazy(c);
        }
        if (g == null) {
            return 0;
        }
        ensureProvider(g.provider);
        return g.advance < 0 ? 0 : g.advance;
    }

    /** 诊断用：按字形前进宽度累加整行，和 FontRenderer#getStringWidth 对比。 */
    public static int measure(String s) {
        if (s == null) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int a = advance(s.charAt(i));
            if (a > 0) {
                w += a;
            }
        }
        return w;
    }

    /** 语义完全照 {@code FontRenderer#getStringWidth}：§ 码跳过下一个字符，粗体每字符 +1。 */
    public static int stringWidth(String s) {
        if (s == null) {
            return 0;
        }
        int total = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            int w = advance(s.charAt(i));
            if (w < 0 && i < s.length() - 1) {
                i++;
                char code = s.charAt(i);
                if (code == 'l' || code == 'L') {
                    bold = true;
                } else if (code == 'r' || code == 'R') {
                    bold = false;
                }
                w = 0;
            }
            total += w;
            if (bold && w > 0) {
                total++;
            }
        }
        return total;
    }

    /** 诊断用：{@link #advance} 被调用的累计次数。 */
    public static int calls() {
        return CALLS;
    }

    /** 画一个字形，返回前进宽度（语义同 FontRenderer 的字符绘制方法）。 */
    public static float draw(float x, float y, char c, boolean italic) {
        if (c == ' ') {
            return (float) SPACE_ADVANCE;
        }
        Glyph g = GLYPHS[c];
        if (g == null) {
            g = lazy(c);
        }
        if (g == null) {
            return 0.0F;
        }
        ensureProvider(g.provider);
        if (g.advance < 0 || g.provider.texture <= 0) {
            return 0.0F;
        }
        // 对齐到整数像素：调用方常用 (width - textWidth) / 2 这类半像素坐标，
        // 字形一旦落在半像素上，GL_LINEAR 采样就会把笔画糊成两像素宽。
        float top = Math.round(y) + BASELINE - g.ascent;
        float shear = italic ? 1.0F : 0.0F;
        float left = Math.round(x) + g.xOff;
        float right = left + g.width;
        float bottom = top + g.height;
        GlStateManager.func_179144_i(g.provider.texture);
        GlStateManager.func_187447_r(GL11.GL_QUADS);
        GlStateManager.func_187426_b(g.u0, g.v0);
        GlStateManager.func_187435_e(left + shear, top, 0.0F);
        GlStateManager.func_187426_b(g.u0, g.v1);
        GlStateManager.func_187435_e(left - shear, bottom, 0.0F);
        GlStateManager.func_187426_b(g.u1, g.v1);
        GlStateManager.func_187435_e(right - shear, bottom, 0.0F);
        GlStateManager.func_187426_b(g.u1, g.v0);
        GlStateManager.func_187435_e(right + shear, top, 0.0F);
        GlStateManager.func_187437_J();
        return (float) g.advance;
    }

    // ---------------------------------------------------------------- 装载

    /** 资源包重载后调用：下一次查询时重新解析字体定义。 */
    public static void reload() {
        if (!ENABLED) {
            return;
        }
        for (Provider p : providers) {
            if (p.texture > 0) {
                PENDING_DELETE.add(p.texture);
            }
            p.texture = 0;
            p.loaded = false;
            p.failed = false;
        }
        java.util.Arrays.fill(GLYPHS, null);
        java.util.Arrays.fill(MISSING, false);
        if (TTF != null) {
            TTF.release();
            TTF = null;
        }
        providers = new Provider[0];
        glyphCount = 0;
        available = false;
        dirty = true;
        ensure();
    }

    /**
     * 把「被材质包/字体包覆盖的 legacy {@code ascii.png}」包装成一个 1.13+ 风格的 bitmap provider。
     *
     * <p>1.12.2 的 {@code FontRenderer.func_78266_a} 把 UV 写死成 {@code /128.0F}、格子写死成 8px，
     * 所以 2x（256×256）的 {@code ascii.png} 只会画出左上角 1/4 并错位——这就是「加载了字体包却
     * 看不出效果」的真正原因（不是 HD 引擎抢了它）。这里按 1.13+ bitmap provider 的算法直接画那张图：
     * 格子尺寸按图片自适应，墨迹宽度决定前进宽度，于是字体包的拉丁字形能正常生效。</p>
     *
     * <p>provider 顺序上排在 {@code font/default.json} 前面，{@link #GLYPHS} 的填充是「先到先得」，
     * 所以拉丁字符走字体包、CJK/emoji 继续由材质包的 default.json 兜底。</p>
     *
     * @return null = 没人覆盖原版 ascii.png（或图不可用），不该插入这个 provider
     */
    private static Provider legacyAscii(Minecraft mc) {
        try {
            IResource res = rawResource(mc, LEGACY_ASCII);
            if (res == null) {
                return null;
            }
            String pack = res.func_177240_d();
            if (pack == null || "Default".equals(pack)) {
                return null; // 栈顶就是原版 128×128，原版管线画得好好的
            }
            int[] dims = pngSize(mc, LEGACY_ASCII);
            if (dims == null || dims[0] < 16 || dims[1] < 16) {
                return null;
            }
            int cols = 16;
            int rows = 16;
            int cellW = dims[0] / cols;
            int cellH = dims[1] / rows;
            if (cellW <= 0 || cellH <= 0) {
                return null;
            }
            int height = 8; // 1.12.2 的 ascii 字形固定 8px 高（行距 10 也是这么来的）
            float scale = (float) height / (float) cellH;
            Provider p = new Provider();
            p.file = "minecraft:textures/font/ascii.png";
            p.location = LEGACY_ASCII;
            p.height = height;
            p.ascent = height - 1;
            p.cols = cols;
            p.rows = rows;
            p.cellW = cellW;
            p.cellH = cellH;
            p.scale = scale;
            p.drawW = Math.max(1, Math.round(cellW * scale));
            p.drawH = height;
            p.codepoints = codepoints(AsciiLayout.GRID);
            Debug.info("hdfont: legacy ascii.png from '" + pack + "' img=" + dims[0] + "x" + dims[1]
                    + " cell=" + cellW + "x" + cellH + " -> override vanilla ascii grid");
            return p;
        } catch (Throwable t) {
            Debug.info("hdfont: legacy ascii probe failed " + t);
            return null;
        }
    }

    private static void ensure() {
        if (!dirty) {
            return;
        }
        if (!ENABLED) {
            dirty = false;
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return;
        }
        dirty = false;
        try {
            List<Provider> list = new ArrayList<Provider>();
            // 字体包/材质包自带的 ascii.png 排第一：它的拉丁字形优先于 default.json 里的拉丁页，
            // 这样「后加载的字体包」无论放在资源列表哪个位置都能生效。
            Provider legacy = legacyAscii(mc);
            if (legacy != null) {
                list.add(legacy);
            }
            ResourceLocation json = new ResourceLocation("minecraft", "font/default.json");
            IResource res = topResource(mc, json);
            if (res != null) {
                String text;
                try (InputStream in = res.func_110527_b()) {
                    text = new String(ByteStreams.toByteArray(in), "UTF-8");
                }
                text = stripComments(text);
                JsonObject root = new JsonParser().parse(text).getAsJsonObject();
                JsonArray arr = root.getAsJsonArray("providers");
                for (JsonElement el : arr) {
                    Provider p = parseProvider(mc, el.getAsJsonObject());
                    if (p != null) {
                        list.add(p);
                    }
                }
            } else if (legacy == null && TTF == null) {
                Debug.info("hdfont: no font/default.json and no pack ascii.png, keeping vanilla 8px font");
                return;
            } else {
                // 只有字体包自己的 ascii.png（或 ttf）时照样接管：没字形的字符由 transformer
                // 交回原版方法画（见 HdFont.has），所以中文不会因为缺 provider 而消失。
                Debug.info("hdfont: no font/default.json, using the pack's own font resource only");
            }
            providers = list.toArray(new Provider[list.size()]);
            int count = 0;
            for (Provider p : providers) {
                for (int i = 0; i < p.codepoints.length; i++) {
                    int cp = p.codepoints[i];
                    if (cp <= 0 || cp >= 65536) {
                        continue;
                    }
                    if (GLYPHS[cp] != null) {
                        continue; // 先出现的 provider 优先（emoji 页不会被 CJK 页顶掉）
                    }
                    GLYPHS[cp] = new Glyph(p, i);
                    count++;
                }
            }
            glyphCount = count;
            available = count > 0 || TTF != null;
            int maxH = 0;
            for (Provider p : providers) {
                if (p.drawH > maxH) {
                    maxH = p.drawH;
                }
            }
            lineHeight = maxH > 0 ? Math.max(9, maxH) : 9;
            Debug.info("hdfont: providers=" + providers.length + " glyphs=" + glyphCount
                    + " lineHeight=" + lineHeight
                    + " -> " + (available ? "HD font ON" : "HD font OFF"));
        } catch (Throwable t) {
            Debug.info("hdfont: parse failed " + t);
            available = false;
        }
    }

    private static Provider parseProvider(Minecraft mc, JsonObject o) {
        try {
            String type = o.has("type") ? o.get("type").getAsString() : "bitmap";
            if ("ttf".equals(type)) {
                return parseTtf(mc, o);
            }
            if (!"bitmap".equals(type)) {
                Debug.info("hdfont: skip provider type=" + type);
                return null;
            }
            String file = o.get("file").getAsString();
            int height = o.has("height") ? o.get("height").getAsInt() : 8;
            int ascent = o.has("ascent") ? o.get("ascent").getAsInt() : height - 1;
            JsonArray chars = o.getAsJsonArray("chars");
            int rows = chars.size();
            int cols = 0;
            int[][] cps = new int[rows][];
            for (int r = 0; r < rows; r++) {
                JsonElement rowEl = chars.get(r);
                String s;
                if (rowEl.isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement cell : rowEl.getAsJsonArray()) {
                        sb.append(cell.getAsString());
                    }
                    s = sb.toString();
                } else {
                    s = rowEl.getAsString();
                }
                cps[r] = codepoints(s);
                cols = Math.max(cols, cps[r].length);
            }
            if (rows <= 0 || cols <= 0) {
                return null;
            }
            ResourceLocation rl = new ResourceLocation(file);
            int[] dims = pngSize(mc, rl);
            if (dims == null) {
                Debug.info("hdfont: cannot size texture " + file);
                return null;
            }
            int cellW = dims[0] / cols;
            int cellH = dims[1] / rows;
            if (cellW <= 0 || cellH <= 0) {
                Debug.info("hdfont: bad cell size " + file + " img=" + dims[0] + "x" + dims[1]
                        + " cells=" + cols + "x" + rows);
                return null;
            }
            float scale = (float) height / (float) cellH;
            int drawW = Math.max(1, Math.round(cellW * scale));
            int drawH = Math.max(1, height);
            Provider p = new Provider();
            p.file = file;
            p.location = rl;
            p.height = height;
            p.ascent = ascent;
            p.cols = cols;
            p.rows = rows;
            p.cellW = cellW;
            p.cellH = cellH;
            p.scale = scale;
            p.drawW = drawW;
            p.drawH = drawH;
            p.codepoints = new int[cols * rows];
            java.util.Arrays.fill(p.codepoints, -1);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cps[r].length && c < cols; c++) {
                    p.codepoints[r * cols + c] = cps[r][c];
                }
            }
            return p;
        } catch (Throwable t) {
            Debug.info("hdfont: provider parse failed " + t);
            return null;
        }
    }

    /**
     * ttf 的额外放大倍率。
     *
     * <p>现代版本把 ttf 的 {@code size} 当「行高像素」用：原版默认 11，正好对应 8px 高的
     * ascii 字形。有些字体包写 {@code size:5.5}（原版一半），字形缩到 5.5 逻辑像素后笔画
     * 只剩亚像素宽度，抗锯齿一摊就变成灰蒙蒙的细线——1.21.2 上同样是这个样子，属于包本身
     * 的设定。想看得清楚就得放大，所以这里给一个「可读性下限」：</p>
     *
     * <ul>
     *   <li>{@code -Dtooltipfix.hdfont.ttfscale=N}（N &gt; 0）：直接按 N 倍放大，1.0 = 严格
     *       按包声明的 size（关掉下限）；</li>
     *   <li>不设该属性：自动把逻辑字号抬到 {@link #TTF_MIN_EM}（= 原版 ascii 的 8px），
     *       本来就不小于 8 的包不受影响；</li>
     *   <li>上限 {@link #TTF_MAX_SCALE} 倍，避免异常 json 把字放到撑破界面。</li>
     * </ul>
     */
    private static float ttfScale(float size) {
        String prop = System.getProperty("tooltipfix.hdfont.ttfscale");
        float scale;
        if (prop != null) {
            try {
                scale = Float.parseFloat(prop.trim());
            } catch (NumberFormatException e) {
                scale = 0.0F;
            }
        } else {
            scale = 0.0F;
        }
        if (scale <= 0.0F) {
            scale = size > 0.0F ? Math.max(1.0F, TTF_MIN_EM / size) : 1.0F;
        }
        return Math.min(TTF_MAX_SCALE, scale);
    }

    /**
     * {@code "type":"ttf"} provider：1.13+ 的矢量字体，1.12.2 完全没有对应实现。
     *
     * <p>这里用 AWT 把它栅格化：{@code size} 是逻辑字号（GUI px 的 em 高度），
     * {@code oversample} 是栅格化倍率（现代版本用来抗锯齿）。字形按需生成、拼进一张图集，
     * 见 {@link Ttf}。</p>
     */
    private static Provider parseTtf(Minecraft mc, JsonObject o) {
        try {
            if (TTF != null) {
                Debug.info("hdfont: extra ttf provider ignored (only one lazy provider is supported)");
                return null;
            }
            String file = o.get("file").getAsString();
            float size = o.has("size") ? o.get("size").getAsFloat() : 11.0F;
            int oversample = o.has("oversample") ? o.get("oversample").getAsInt() : 2;
            float shiftX = 0.0F;
            float shiftY = 0.0F;
            if (o.has("shift") && o.get("shift").isJsonArray()) {
                JsonArray sh = o.getAsJsonArray("shift");
                if (sh.size() >= 1) {
                    shiftX = sh.get(0).getAsFloat();
                }
                if (sh.size() >= 2) {
                    shiftY = sh.get(1).getAsFloat();
                }
            }
            ResourceLocation rl = new ResourceLocation(file);
            // 1.13+ 的 file 是相对 assets/<ns>/font/ 的（"minecraft:xxx.ttf" → font/xxx.ttf）
            ResourceLocation font = new ResourceLocation(rl.func_110624_b(), "font/" + rl.func_110623_a());
            IResource res = topResource(mc, font);
            if (res == null) {
                Debug.info("hdfont: ttf not in resource stack " + font);
                return null;
            }
            java.awt.Font awt;
            try (InputStream in = res.func_110527_b()) {
                awt = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
            }
            float scale = ttfScale(size);
            float em = size * scale;
            awt = awt.deriveFont(em * Math.max(1, oversample));
            java.awt.font.FontRenderContext frc =
                    new java.awt.font.FontRenderContext(null, true, false);
            java.awt.font.LineMetrics lm = awt.getLineMetrics("Ag\u591c", frc);
            float asc = lm.getAscent() / Math.max(1, oversample);
            float desc = lm.getDescent() / Math.max(1, oversample);
            Provider p = new Provider();
            p.file = file;
            p.location = rl;
            p.lazy = true;
            p.drawH = Math.max(1, Math.round(asc + desc));
            p.height = p.drawH;
            p.ascent = Math.round(asc);
            p.drawW = 0;
            p.codepoints = new int[0];
            TTF = new Ttf(p, awt, Math.max(1, oversample), shiftX * scale, shiftY * scale);
            Debug.info("hdfont: ttf provider '" + file + "' pack='" + res.func_177240_d()
                    + "' size=" + size + " scale=" + scale + " oversample=" + oversample
                    + " ascent=" + p.ascent + " height=" + p.drawH);
            return p;
        } catch (Throwable t) {
            Debug.info("hdfont: ttf provider failed " + t);
            return null;
        }
    }

    private static void ensureProvider(Provider p) {
        if (p.lazy || p.loaded || p.failed) {
            return;
        }
        p.failed = true; // 先置位，避免重入
        try {
            Minecraft mc = Minecraft.func_71410_x();
            IResource res = mc == null ? null : topResource(mc, p.location);
            if (res == null) {
                Debug.info("hdfont: texture not in resource stack " + p.file);
                return;
            }
            BufferedImage img;
            try (InputStream in = res.func_110527_b()) {
                img = ImageIO.read(in);
            }
            if (img == null) {
                Debug.info("hdfont: ImageIO cannot read " + p.file);
                return;
            }
            int sw = img.getWidth();
            int sh = img.getHeight();
            int[] src = img.getRGB(0, 0, sw, sh, null, 0, sw);

            // 1) 用原图算墨迹宽度（与 modern BitmapProvider 同一算法）
            int[] ink = new int[p.codepoints.length];
            int[] inkLeft = new int[p.codepoints.length];
            for (int r = 0; r < p.rows; r++) {
                for (int c = 0; c < p.cols; c++) {
                    int x0 = c * p.cellW;
                    int y0 = r * p.cellH;
                    int first = -1;
                    int last = -1;
                    for (int x = 0; x < p.cellW; x++) {
                        int px = x0 + x;
                        for (int y = 0; y < p.cellH; y++) {
                            if (((src[(y0 + y) * sw + px] >>> 24)) > 1) {
                                if (first < 0) {
                                    first = x;
                                }
                                last = x;
                                break;
                            }
                        }
                    }
                    int i = r * p.cols + c;
                    inkLeft[i] = first < 0 ? 0 : first;
                    ink[i] = first < 0 ? 0 : last - first + 1;
                }
            }

            // 2) 按「逻辑尺寸的 DENSITY 倍」取样：材质包的字形图集本身就是 4 倍（36px 格子
            //    画 9px 字），取 4 倍 = 原图直传，在 guiScale 4 下正好 1:1，笔画不会被插值糊掉；
            //    低 guiScale 时由 GL 线性采样缩下去（有抗锯齿）。可用 -Dtooltipfix.hdfont.density 调。
            int factor = Math.max(1, p.cellH / (DENSITY * Math.max(1, p.drawH)));
            int cw = Math.max(1, p.cellW / factor);
            int ch = Math.max(1, p.cellH / factor);
            int tw = cw * p.cols;
            int th = ch * p.rows;
            int[] small = resample(src, sw, sh, tw, th, p.cols, p.rows, p.cellW, p.cellH);
            p.texture = upload(small, tw, th);

            // 3) 回填几何与前进宽度：只画墨迹那一块，避免「画满整格」把右边界顶出测量宽度
            for (int i = 0; i < p.codepoints.length; i++) {
                int cp = p.codepoints[i];
                if (cp <= 0 || cp >= 65536) {
                    continue;
                }
                Glyph g = GLYPHS[cp];
                if (g == null || g.provider != p) {
                    continue;
                }
                float s = p.scale;
                g.xOff = inkLeft[i] * s;
                g.width = ink[i] * s;
                float cellX = (i % p.cols) * (float) cw;
                float ux0 = inkLeft[i] / (float) factor;
                float ux1 = (inkLeft[i] + ink[i]) / (float) factor;
                g.u0 = (cellX + ux0) / (float) tw;
                g.u1 = (cellX + ux1) / (float) tw;
                int row = i / p.cols;
                g.v0 = row * (float) ch / (float) th;
                g.v1 = (row + 1) * (float) ch / (float) th;
                g.advance = (int) (0.5F + ink[i] * s) + 1;
            }
            p.loaded = true;
            p.failed = false;
            Debug.info("hdfont: provider " + p.file + " img=" + sw + "x" + sh
                    + " cells=" + p.cols + "x" + p.rows + " cell=" + p.cellW + "x" + p.cellH
                    + " drawn=" + p.drawW + "x" + p.drawH + " tex=" + tw + "x" + th
                    + " div=" + factor + " gl=" + p.texture);
        } catch (Throwable t) {
            Debug.info("hdfont: provider load failed " + p.file + " " + t);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static int[] codepoints(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            n++;
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            out[k++] = cp;
        }
        return out;
    }

    private static IResource topResource(Minecraft mc, ResourceLocation rl) {
        IResource res = rawResource(mc, rl);
        if (res != null) {
            return res;
        }
        // 1.13 时代的老材质包把字形图放在 textures/font/ 下，但 default.json 里写的是 font/xxx.png，
        // 现代版本靠这条前缀兜底，这里照做。
        String path = rl.func_110623_a();
        if (!path.startsWith("textures/")) {
            res = rawResource(mc, new ResourceLocation(rl.func_110624_b(), "textures/" + path));
        }
        return res;
    }

    private static IResource rawResource(Minecraft mc, ResourceLocation rl) {
        if (mc == null) {
            return null;
        }
        try {
            IResource res = mc.func_110442_L().func_110536_a(rl);
            if (res != null) {
                return res;
            }
        } catch (java.io.IOException ignored) {
            // 单资源查找失败就退回全量列表，见下
        }
        try {
            List<IResource> list = mc.func_110442_L().func_135056_b(rl);
            if (list != null && !list.isEmpty()) {
                return list.get(list.size() - 1);
            }
        } catch (java.io.IOException ignored) {
            // 两种查法都失败：交给调用方记日志
        }
        return null;
    }

    private static int[] pngSize(Minecraft mc, ResourceLocation rl) {
        try {
            IResource res = topResource(mc, rl);
            if (res == null) {
                Debug.info("hdfont: pngSize no resource " + rl);
                return null;
            }
            byte[] head = new byte[24];
            int read = 0;
            try (InputStream in = res.func_110527_b()) {
                while (read < head.length) {
                    int n = in.read(head, read, head.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            if (read < 24) {
                Debug.info("hdfont: pngSize short read " + read + " " + rl);
                return null;
            }
            if ((head[0] & 0xFF) != 0x89 || head[1] != 'P') {
                Debug.info("hdfont: pngSize bad signature " + rl);
                return null;
            }
            int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16)
                    | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
            int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16)
                    | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
            if (w <= 0 || h <= 0) {
                Debug.info("hdfont: pngSize bad dims " + w + "x" + h + " " + rl);
                return null;
            }
            return new int[]{w, h};
        } catch (Throwable t) {
            Debug.info("hdfont: pngSize error " + t + " " + rl);
            return null;
        }
    }

    /** 逐格 box filter 缩小；透明（alpha&lt;=1）的像素按无贡献处理，避免黑色描边渗色。 */
    private static int[] resample(int[] src, int sw, int sh, int tw, int th,
                                  int cols, int rows, int cellW, int cellH) {
        int[] out = new int[tw * th];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int dx0 = c * (tw / cols);
                int dy0 = r * (th / rows);
                int dw = tw / cols;
                int dh = th / rows;
                for (int dy = 0; dy < dh; dy++) {
                    int sy0 = r * cellH + dy * cellH / dh;
                    int sy1 = r * cellH + Math.max(dy * cellH / dh + 1, (dy + 1) * cellH / dh);
                    for (int dx = 0; dx < dw; dx++) {
                        int sx0 = c * cellW + dx * cellW / dw;
                        int sx1 = c * cellW + Math.max(dx * cellW / dw + 1, (dx + 1) * cellW / dw);
                        long a = 0L;
                        long rr = 0L;
                        long gg = 0L;
                        long bb = 0L;
                        int total = (sx1 - sx0) * (sy1 - sy0);
                        for (int sy = sy0; sy < sy1 && sy < sh; sy++) {
                            for (int sx = sx0; sx < sx1 && sx < sw; sx++) {
                                int p = src[sy * sw + sx];
                                int al = p >>> 24;
                                if (al <= 1) {
                                    continue;
                                }
                                a += al;
                                rr += ((p >> 16) & 0xFF) * al;
                                gg += ((p >> 8) & 0xFF) * al;
                                bb += (p & 0xFF) * al;
                            }
                        }
                        int idx = (dy0 + dy) * tw + dx0 + dx;
                        if (a <= 0L || total <= 0) {
                            out[idx] = 0;
                            continue;
                        }
                        int alpha = (int) (a / total);
                        int red = (int) (rr / a);
                        int green = (int) (gg / a);
                        int blue = (int) (bb / a);
                        out[idx] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                    }
                }
            }
        }
        return out;
    }

    private static int upload(int[] argb, int w, int h) {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int p : argb) {
            buf.put((byte) (p >> 16));
            buf.put((byte) (p >> 8));
            buf.put((byte) p);
            buf.put((byte) (p >>> 24));
        }
        buf.flip();
        int id = GL11.glGenTextures();
        GlStateManager.func_179144_i(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    /** 去掉 JSON 里的 {@code //} 行注释（材质包 author 行）。 */
    private static String stripComments(String s) {
        if (s.indexOf("//") < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 数据

    /**
     * ttf 字形的按需栅格化 + 图集。
     *
     * <p>1.13+ 的 ttf provider 不声明字符表，任何字符都可能出现，所以不能像 bitmap provider
     * 那样在装载时一次性建表。这里用 AWT 现画现拼：第一次用到某字符时栅格化成位图，
     * 用货架算法塞进一张 2048² 的图集（每次只上传那一小块），之后就是一个普通 Glyph。</p>
     */
    private static final class Ttf {

        private static final int ATLAS = 2048;

        private final Provider provider;
        private final java.awt.Font font;
        private final int oversample;
        private final float shiftX;
        private final float shiftY;

        private final java.awt.font.FontRenderContext frc =
                new java.awt.font.FontRenderContext(null, true, false);

        private int texture;
        private int[] pixels;
        private int penX;
        private int penY;
        private int shelfH;
        private int slotX;
        private int slotY;
        private int count;

        Ttf(Provider provider, java.awt.Font font, int oversample, float shiftX, float shiftY) {
            this.provider = provider;
            this.font = font;
            this.oversample = oversample;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
        }

        void release() {
            if (texture > 0) {
                PENDING_DELETE.add(texture);
                texture = 0;
            }
            pixels = null;
        }

        private boolean init() {
            pixels = new int[ATLAS * ATLAS];
            texture = upload(pixels, ATLAS, ATLAS);
            provider.texture = texture;
            provider.loaded = true;
            Debug.info("hdfont: ttf atlas " + ATLAS + "x" + ATLAS + " gl=" + texture);
            return texture > 0;
        }

        /** 取一个字符的字形；字体里没有这个字形时返回 null。 */
        Glyph glyph(int cp) {
            if (!font.canDisplay(cp)) {
                // 字体里没有这个字形：不画 .notdef 方框，交给原版管线（中文仍能正常显示）
                return null;
            }
            if (texture <= 0 && !init()) {
                return null;
            }
            String s = new String(Character.toChars(cp));
            java.awt.font.GlyphVector gv = font.createGlyphVector(frc, s);
            if (gv.getNumGlyphs() < 1) {
                return null;
            }
            float advance = gv.getGlyphMetrics(0).getAdvance() / (float) oversample;
            java.awt.Rectangle pb = gv.getGlyphPixelBounds(0, frc, 0.0F, 0.0F);
            if (pb.width <= 0 || pb.height <= 0) {
                // 空格/组合符：只有前进宽度，没有墨迹
                Glyph blank = new Glyph(provider, 0);
                blank.width = 0.0F;
                blank.height = 0.0F;
                blank.ascent = 0.0F;
                blank.advance = Math.max(1, Math.round(advance));
                return blank;
            }
            if (pb.width > ATLAS || pb.height > ATLAS || !place(pb.width, pb.height)) {
                Debug.info("hdfont: ttf atlas full at " + count + " glyphs, '" + (char) cp + "' dropped");
                provider.failed = true;
                return null;
            }
            BufferedImage img = new BufferedImage(pb.width, pb.height,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.setFont(font);
            g.drawString(s, -pb.x, -pb.y);
            g.dispose();
            for (int y = 0; y < pb.height; y++) {
                img.getRGB(0, y, pb.width, 1, pixels, (slotY + y) * ATLAS + slotX, pb.width);
            }
            uploadRegion(slotX, slotY, pb.width, pb.height);

            Glyph glyph = new Glyph(provider, 0);
            glyph.width = pb.width / (float) oversample;
            glyph.height = pb.height / (float) oversample;
            // pb.y 是墨迹顶相对基线的偏移（往上为负），换成本引擎的「基线到墨迹顶」距离
            glyph.ascent = shiftY - pb.y / (float) oversample;
            glyph.xOff = shiftX;
            glyph.advance = Math.max(1, Math.round(advance));
            glyph.u0 = slotX / (float) ATLAS;
            glyph.v0 = slotY / (float) ATLAS;
            glyph.u1 = (slotX + pb.width) / (float) ATLAS;
            glyph.v1 = (slotY + pb.height) / (float) ATLAS;
            count++;
            if (count <= 3 || count % 512 == 0) {
                Debug.info("hdfont: ttf glyph '" + (char) cp + "' " + pb.width + "x" + pb.height
                        + " adv=" + glyph.advance + " n=" + count);
            }
            return glyph;
        }

        /** 货架算法：一行一行往右排，排满换行；返回 false = 图集满。 */
        private boolean place(int w, int h) {
            if (w + 2 > ATLAS) {
                return false;
            }
            if (penX + w + 1 > ATLAS) {
                penX = 0;
                penY += shelfH + 1;
                shelfH = 0;
            }
            if (penY + h + 1 > ATLAS) {
                return false;
            }
            slotX = penX;
            slotY = penY;
            penX += w + 1;
            if (h > shelfH) {
                shelfH = h;
            }
            return true;
        }

        private void uploadRegion(int x, int y, int w, int h) {
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int yy = 0; yy < h; yy++) {
                int row = (y + yy) * ATLAS + x;
                for (int xx = 0; xx < w; xx++) {
                    int p = pixels[row + xx];
                    buf.put((byte) (p >> 16));
                    buf.put((byte) (p >> 8));
                    buf.put((byte) p);
                    buf.put((byte) (p >>> 24));
                }
            }
            buf.flip();
            GlStateManager.func_179144_i(texture);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        }
    }

    private static final class Glyph {
        final Provider provider;
        float height;
        float ascent;
        /** 水平和垂直 UV 都在装载贴图时才回填：只覆盖墨迹那一块，宽度不超过 advance-1。 */
        float u0;
        float u1;
        float v0;
        float v1;
        float width;
        float xOff;
        int advance = -1;

        Glyph(Provider p, int cell) {
            this.provider = p;
            this.height = (float) p.drawH;
            this.ascent = (float) p.ascent;
        }
    }

    private static final class Provider {
        String file;
        ResourceLocation location;
        int height;
        int ascent;
        int cols;
        int rows;
        int cellW;
        int cellH;
        int drawW;
        int drawH;
        float scale;
        int[] codepoints = new int[0];
        int texture;
        boolean loaded;
        boolean failed;
        /** ttf provider：字形按需栅格化，{@link #codepoints} 为空。 */
        boolean lazy;
    }
}
