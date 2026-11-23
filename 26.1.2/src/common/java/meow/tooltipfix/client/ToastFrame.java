package meow.tooltipfix.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import meow.tooltipfix.mixin.GuiGraphicsExtractorAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** System toast nine-slice correction for pack-provided sprites only. */
public final class ToastFrame {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-toast");
    private static final Map<TextureAtlasSprite, Slice> CACHE = new WeakHashMap<>();
    private static final Set<String> LOGGED = new HashSet<>();

    private static String config = "";
    private static long configStamp = Long.MIN_VALUE;
    private static File configFile;
    private static int tick;

    private ToastFrame() {
    }

    /** 配置文件全文（小写）。 */
    private static String config() {
        if (configFile == null) {
            configFile = new File(new File(Minecraft.getInstance().gameDirectory, "config"), "tooltipfix-toast.txt");
            config = readConfig();
            configStamp = stamp();
        }
        if (++tick % 20 == 0) {
            long now = stamp();
            if (now != configStamp) {
                configStamp = now;
                config = readConfig();
            }
        }
        return config;
    }

    /** {@code off} | {@code clamp} | {@code fix}。 */
    public static String mode() {
        String c = config();
        if (c.contains("off")) {
            return "off";
        }
        if (c.contains("clamp")) {
            return "clamp";
        }
        return "fix";
    }

    public static boolean selftest() {
        return Boolean.getBoolean("tooltipfix.selftest") || config().contains("selftest");
    }

    public static void reset() {
        CACHE.clear();
        LOGGED.clear();
        configFile = null;
        config = "";
        configStamp = Long.MIN_VALUE;
    }

    /** 替换原版那一次 {@code blitSprite}。任何可疑情况都原样交回原版。 */
    public static void draw(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite,
                            int x, int y, int w, int h) {
        String mode = mode();
        TextureAtlasSprite tas = "off".equals(mode) ? null : atlasSprite(g, sprite);
        Slice s = tas == null ? null : slice(sprite, tas);
        if (s == null) {
            g.blitSprite(pipeline, sprite, x, y, w, h);
            return;
        }

        int[] b = "clamp".equals(mode) ? clamp(s, w, h) : fit(s, w, h);
        int l = b[0];
        int r = b[1];
        int t = b[2];
        int bo = b[3];

        int[] vanilla = clamp(s, w, h);
        boolean same = l == vanilla[0] && r == vanilla[1] && t == vanilla[2] && bo == vanilla[3];
        int midW = w - l - r;
        int midH = h - t - bo;
        boolean innerEmpty = midW <= 0 || midH <= 0 || s.width() - l - r <= 0 || s.height() - t - bo <= 0;
        boolean tiles = !s.stretchInner() && !innerEmpty;
        if (same || tiles) {
            // 原版此刻不会夹边框（或需要平铺内区），交给原版最稳
            g.blitSprite(pipeline, sprite, x, y, w, h);
            logOnce(sprite, "vanilla(same=" + same + ",tile=" + tiles + ")");
            return;
        }

        int sw = s.width();
        int sh = s.height();
        int u0 = s.left();
        int v0 = s.top();
        int uw = sw - s.left() - s.right();
        int vh = sh - s.top() - s.bottom();

        int sl = "clamp".equals(mode) ? s.left() : Math.min(s.left(), l);
        int sr = "clamp".equals(mode) ? s.right() : Math.min(s.right(), r);
        int st = "clamp".equals(mode) ? s.top() : Math.min(s.top(), t);
        int sb = "clamp".equals(mode) ? s.bottom() : Math.min(s.bottom(), bo);
        // Fixed bands are copied 1:1; excess inner padding is cropped, not rescaled.
        part(g, pipeline, tas, sw, sh, 0, 0, sl, st, x, y, l, t);
        part(g, pipeline, tas, sw, sh, sw - sr, 0, sr, st, x + w - r, y, r, t);
        part(g, pipeline, tas, sw, sh, 0, sh - sb, sl, sb, x, y + h - bo, l, bo);
        part(g, pipeline, tas, sw, sh, sw - sr, sh - sb, sr, sb, x + w - r, y + h - bo, r, bo);
        // 四边
        part(g, pipeline, tas, sw, sh, u0, 0, uw, st, x + l, y, midW, t);
        part(g, pipeline, tas, sw, sh, u0, sh - sb, uw, sb, x + l, y + h - bo, midW, bo);
        part(g, pipeline, tas, sw, sh, 0, v0, sl, vh, x, y + t, l, midH);
        part(g, pipeline, tas, sw, sh, sw - sr, v0, sr, vh, x + w - r, y + t, r, midH);
        // 内区
        part(g, pipeline, tas, sw, sh, u0, v0, uw, vh, x + l, y + t, midW, midH);
        logOnce(sprite, "fixed b=" + l + "," + t + "," + r + "," + bo + " target=" + w + "x" + h);
    }

    /** 一块九宫格：源矩形按 srcW/srcH 归一化，走与 vanilla 相同的 innerBlit。 */
    private static void part(GuiGraphicsExtractor g, RenderPipeline pipeline, TextureAtlasSprite tas, int srcW, int srcH,
                             int su, int sv, int suw, int svh, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || suw <= 0 || svh <= 0) {
            return;
        }
        float u0 = tas.getU((float) su / (float) srcW);
        float u1 = tas.getU((float) (su + suw) / (float) srcW);
        float v0 = tas.getV((float) sv / (float) srcH);
        float v1 = tas.getV((float) (sv + svh) / (float) srcH);
        // 参数顺序是 (texture, x0, x1, y0, y1, u0, u1, v0, v1)
        ((GuiGraphicsExtractorAccess) (Object) g).tooltipfix$innerBlit(pipeline, tas.atlasLocation(),
                x, x + w, y, y + h, u0, u1, v0, v1, -1);
    }

    /** 原版行为：每边最多夹到目标的一半。 */
    private static int[] clamp(Slice s, int w, int h) {
        int halfW = w / 2;
        int halfH = h / 2;
        return new int[]{
                Math.min(s.left(), halfW), Math.min(s.right(), halfW),
                Math.min(s.top(), halfH), Math.min(s.bottom(), halfH)
        };
    }

    /** Keep the larger (potentially decorated) band intact; fit the smaller opposite band. */
    private static int[] fit(Slice s, int w, int h) {
        int[] horizontal = fitAxis(s.left(), s.right(), w);
        int[] vertical = fitAxis(s.top(), s.bottom(), h);
        return new int[]{horizontal[0], horizontal[1], vertical[0], vertical[1]};
    }

    private static int[] fitAxis(int first, int second, int size) {
        first = Math.min(first, Math.max(0, size));
        second = Math.min(second, Math.max(0, size));
        if (first + second > size) {
            if (first >= second) second = Math.max(0, size - first);
            else first = Math.max(0, size - second);
        }
        return new int[]{first, second};
    }

    private static TextureAtlasSprite atlasSprite(GuiGraphicsExtractor g, Identifier id) {
        try {
            TextureAtlas atlas = ((GuiGraphicsExtractorAccess) (Object) g).tooltipfix$guiSprites();
            TextureAtlasSprite sprite = atlas == null ? null : atlas.getSprite(id);
            return sprite != null && id.equals(sprite.contents().name()) ? sprite : null;
        } catch (Exception e) {
            LOG.warn("sprite lookup failed for {}: {}", id, e);
            return null;
        }
    }

    private static String readConfig() {
        try {
            File f = configFile;
            if (f == null || !f.isFile()) {
                return "";
            }
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static long stamp() {
        File f = configFile;
        return f != null && f.isFile() ? f.lastModified() * 31 + f.length() : -1L;
    }

    private static Slice slice(Identifier id, TextureAtlasSprite sprite) {
        if (!CACHE.containsKey(sprite)) {
            LOGGED.clear();
            CACHE.put(sprite, load(id, sprite));
        }
        return CACHE.get(sprite);
    }

    private static Slice load(Identifier id, TextureAtlasSprite sprite) {
        Identifier png = Identifier.fromNamespaceAndPath(id.getNamespace(),
                "textures/gui/sprites/" + id.getPath() + ".png");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(png);
        if (resource.isEmpty()) return null;
        String pack = resource.get().sourcePackId();
        if ("vanilla".equals(pack) || "minecraft".equals(pack)) {
            logOnce(id, "skip(builtin pack=" + pack + ")");
            return null;
        }
        var scaling = sprite.contents().getAdditionalMetadata(GuiMetadataSection.TYPE)
                .orElse(GuiMetadataSection.DEFAULT).scaling();
        if (!(scaling instanceof GuiSpriteScaling.NineSlice nine)) return null;
        var b = nine.border();
        LOG.info("slice {} pack={} base={}x{} border={},{},{},{} stretchInner={}", id, pack,
                nine.width(), nine.height(), b.left(), b.top(), b.right(), b.bottom(), nine.stretchInner());
        return new Slice(nine.width(), nine.height(), b.left(), b.top(), b.right(), b.bottom(), nine.stretchInner());
    }

    private static void logOnce(Identifier sprite, String what) {
        String key = sprite + "|" + what;
        if (LOGGED.add(key)) {
            LOG.info("toast frame {} -> {}", sprite, what);
        }
    }

    private record Slice(int width, int height, int left, int top, int right, int bottom, boolean stretchInner) {
    }
}
