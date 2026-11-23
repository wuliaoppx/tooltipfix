package meow.tooltipfix.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Draws a toast background the way the pack asked for it.
 *
 * <p>1.21.1 hard-codes the vanilla tiling (20 / 64 / 60 px caps, v offsets 0 / 16 / 28) and calls
 * {@code blitSprite(sprite, 160, 32, ...)}, so any pack that ships a larger, nine-sliced
 * {@code toast/system} sprite (or one whose base is not 160x32) is sampled with the wrong UVs and
 * shows seams.
 *
 * <p>Vanilla's own nine-slice path is no help either: it clips every border to half the target size
 * and repeats the centre instead of stretching it. Here the borders keep the sizes the pack declared
 * (shrunk only when the toast is too small to hold both sides) and every region is stretched.
 *
 * <p>Mode is hot-reloadable from {@code config/tooltipfix-toast.txt}: {@code off}, {@code clamp}
 * (vanilla behaviour, for comparison) or {@code fix} (default).
 */
public final class ToastFrame {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-toast");
    private static final Map<TextureAtlasSprite, Slice> CACHE = new WeakHashMap<>();
    private static final File MODE_FILE = new File(new File(Minecraft.getInstance().gameDirectory, "config"),
            "tooltipfix-toast.txt");

    private static String mode = readMode();
    private static long modeStamp = stamp();
    private static int tick;

    private ToastFrame() {
    }

    /** {@code off} | {@code clamp} | {@code fix}. */
    public static String mode() {
        if (++tick % 20 == 0) {
            long now = stamp();
            if (now != modeStamp) {
                modeStamp = now;
                mode = readMode();
            }
        }
        return mode;
    }

    /** true when the sprite declares nine_slice scaling, i.e. when vanilla would be wrong. */
    public static boolean has(ResourceLocation sprite) {
        return !"off".equals(mode()) && slice(sprite) != null;
    }

    /** Drops parsed metadata so a resource reload picks up a changed pack. */
    public static void reset() {
        CACHE.clear();
        mode = readMode();
        modeStamp = stamp();
    }

    /** Draws the background of a {@code w} x {@code h} toast at {@code x,y}. */
    public static void draw(GuiGraphics g, ResourceLocation sprite, int x, int y, int w, int h) {
        String m = mode();
        Slice s = "off".equals(m) ? null : slice(sprite);
        if (s == null) {
            g.blitSprite(sprite, x, y, w, h);
            return;
        }

        int bu = Math.round(s.left());
        int bv = Math.round(s.top());
        int br = Math.round(s.right());
        int bb = Math.round(s.bottom());

        int l;
        int r;
        int t;
        int b;
        if ("clamp".equals(m)) {
            // what 1.21.4+ does: every border is cut to half the target size
            l = Math.min(bu, w / 2);
            r = Math.min(br, w / 2);
            t = Math.min(bv, h / 2);
            b = Math.min(bb, h / 2);
        } else {
            // Fit each axis independently. Preserve the larger decorated band (the top band
            // contains CozyUI's entire exclamation mark, including the final pixel row).
            l = Math.min(bu, Math.max(0, w));
            r = Math.min(br, Math.max(0, w));
            t = Math.min(bv, Math.max(0, h));
            b = Math.min(bb, Math.max(0, h));
            if (l + r > w) { if (l >= r) r = Math.max(0, w - l); else l = Math.max(0, w - r); }
            if (t + b > h) { if (t >= b) b = Math.max(0, h - t); else t = Math.max(0, h - b); }
        }

        int midW = w - l - r;
        int midH = h - t - b;
        int srcMidW = s.baseW() - bu - br;
        int srcMidH = s.baseH() - bv - bb;

        TextureAtlasSprite tas = atlasSprite(sprite);
        if (tas == null) {
            g.blitSprite(sprite, x, y, w, h);
            return;
        }

        int sw = s.baseW();
        int sh = s.baseH();
        // Fixed bands stay 1:1. If an opposite band must be reduced, trim its inner
        // padding instead of rescaling its outer outline (or the embedded glyph).
        int sl = "clamp".equals(m) ? bu : Math.min(bu, l);
        int sr = "clamp".equals(m) ? br : Math.min(br, r);
        int st = "clamp".equals(m) ? bv : Math.min(bv, t);
        int sb = "clamp".equals(m) ? bb : Math.min(bb, b);
        // corners
        part(g, tas, sw, sh, 0, 0, sl, st, x, y, l, t);
        part(g, tas, sw, sh, sw - sr, 0, sr, st, x + w - r, y, r, t);
        part(g, tas, sw, sh, 0, sh - sb, sl, sb, x, y + h - b, l, b);
        part(g, tas, sw, sh, sw - sr, sh - sb, sr, sb, x + w - r, y + h - b, r, b);

        // edges
        if (midW > 0 && srcMidW > 0) {
            part(g, tas, sw, sh, bu, 0, srcMidW, st, x + l, y, midW, t);
            part(g, tas, sw, sh, bu, sh - sb, srcMidW, sb, x + l, y + h - b, midW, b);
        }
        if (midH > 0 && srcMidH > 0) {
            part(g, tas, sw, sh, 0, bv, sl, srcMidH, x, y + t, l, midH);
            part(g, tas, sw, sh, sw - sr, bv, sr, srcMidH, x + w - r, y + t, r, midH);
        }
        if (midW > 0 && midH > 0 && srcMidW > 0 && srcMidH > 0) {
            part(g, tas, sw, sh, bu, bv, srcMidW, srcMidH, x + l, y + t, midW, midH);
        }
    }

    /** Stretches the {@code su} x {@code sv} region (sprite units) into {@code w} x {@code h}. */
    private static void part(GuiGraphics g, TextureAtlasSprite tas, int sw, int sh,
                             int su, int sv, int suw, int svh, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || suw <= 0 || svh <= 0) {
            return;
        }
        float u0 = tas.getU((float) su / (float) sw);
        float u1 = tas.getU((float) (su + suw) / (float) sw);
        float v0 = tas.getV((float) sv / (float) sh);
        float v1 = tas.getV((float) (sv + svh) / (float) sh);

        Matrix4f pose = g.pose().last().pose();
        RenderSystem.setShaderTexture(0, tas.atlasLocation());
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Typed as the interface on purpose: the Fabric build remaps the jar without a Minecraft
        // classpath, so a call whose receiver is BufferBuilder cannot be resolved through
        // inheritance and would keep its Mojang name. Calling through VertexConsumer maps cleanly.
        VertexConsumer vc = bb;
        vc.addVertex(pose, (float) x, (float) (y + h), 0.0F).setUv(u0, v1);
        vc.addVertex(pose, (float) (x + w), (float) (y + h), 0.0F).setUv(u1, v1);
        vc.addVertex(pose, (float) (x + w), (float) y, 0.0F).setUv(u1, v0);
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setUv(u0, v0);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();
    }

    private static TextureAtlasSprite atlasSprite(ResourceLocation id) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getGuiSprites().getSprite(id);
        return sprite != null && id.equals(sprite.contents().name()) ? sprite : null;
    }

    private static String readMode() {
        try {
            if (!MODE_FILE.isFile()) {
                return "fix";
            }
            String v = new String(Files.readAllBytes(MODE_FILE.toPath()), StandardCharsets.UTF_8).trim();
            if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("clamp")) {
                return v.toLowerCase();
            }
            return "fix";
        } catch (Exception e) {
            return "fix";
        }
    }

    private static long stamp() {
        return MODE_FILE.isFile() ? MODE_FILE.lastModified() * 31 + MODE_FILE.length() : -1L;
    }

    private static Slice slice(ResourceLocation sprite) {
        TextureAtlasSprite current = atlasSprite(sprite);
        if (current == null) return null;
        if (!CACHE.containsKey(current)) CACHE.put(current, load(sprite));
        return CACHE.get(current);
    }

    private static Slice load(ResourceLocation sprite) {
        ResourceLocation meta = ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(),
                "textures/gui/sprites/" + sprite.getPath() + ".png.mcmeta");
        ResourceLocation png = ResourceLocation.fromNamespaceAndPath(meta.getNamespace(),
                meta.getPath().substring(0, meta.getPath().length() - ".mcmeta".length()));
        var resources = Minecraft.getInstance().getResourceManager();
        Optional<Resource> image = resources.getResource(png);
        Optional<Resource> found = resources.getResource(meta);
        if (image.isEmpty() || found.isEmpty() || !image.get().sourcePackId().equals(found.get().sourcePackId())
                || "vanilla".equals(image.get().sourcePackId())) return null;
        LOG.info("mcmeta found at {} pack={}", meta, found.get().sourcePackId());
        try (Reader rd = new InputStreamReader(found.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(rd).getAsJsonObject();
            JsonObject gui = asObject(root, "gui");
            JsonObject scaling = gui == null ? null : asObject(gui, "scaling");
            if (scaling == null || !"nine_slice".equals(asString(scaling, "type"))) {
                return null;
            }
            int baseW = asInt(scaling, "width", 0);
            int baseH = asInt(scaling, "height", 0);
            if (baseW <= 0 || baseH <= 0) {
                return null;
            }
            float l = 0.0F;
            float t = 0.0F;
            float r = 0.0F;
            float b = 0.0F;
            JsonElement border = scaling.get("border");
            if (border != null && border.isJsonObject()) {
                JsonObject o = border.getAsJsonObject();
                l = asInt(o, "left", 0);
                t = asInt(o, "top", 0);
                r = asInt(o, "right", 0);
                b = asInt(o, "bottom", 0);
            } else if (border != null && border.isJsonPrimitive()) {
                float v = border.getAsFloat();
                l = v;
                t = v;
                r = v;
                b = v;
            }
            LOG.info("slice {} base={}x{} border={},{},{},{}", sprite, baseW, baseH, l, t, r, b);
            return new Slice(baseW, baseH, l, t, r, b);
        } catch (Exception e) {
            LOG.warn("mcmeta parse failed for {}: {}", sprite, e.toString());
            return null;
        }
    }

    private static JsonObject asObject(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String asString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int asInt(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
    }

    private record Slice(int baseW, int baseH, float left, float top, float right, float bottom) {
    }
}
