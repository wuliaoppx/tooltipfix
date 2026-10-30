package meow.tooltipfix.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import meow.tooltipfix.mixin.GuiGraphicsExtractorAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Draws a toast background the way the pack asked for it (26.1.2 / 1.21.4+ pipeline).
 *
 * <p>1.21.4 and later do read {@code gui.scaling} nine_slice metadata, but
 * {@code GuiGraphicsExtractor.blitNineSlicedSprite} clips every border to half the target size
 * ({@code Math.min(border, height / 2)}). A 160x64 sprite with {@code border.top = 26..30} is
 * therefore squashed into a 32 or 44 pixel tall toast, which is what deforms the pack's icon and
 * corners.
 *
 * <p>Here the borders keep the sizes the pack declared and are shrunk only when the toast really
 * cannot hold both sides, so the frame keeps its proportions at every toast height.
 */
public final class ToastFrame {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-toast");
    private static final Map<String, Slice> CACHE = new HashMap<>();
    private static final Map<String, TextureAtlasSprite> SPRITES = new HashMap<>();

    private static String mode;
    private static long modeStamp = Long.MIN_VALUE;
    private static File modeFile;
    private static int tick;

    private ToastFrame() {
    }

    /** {@code off} | {@code clamp} | {@code fix}. */
    public static String mode() {
        if (modeFile == null) {
            modeFile = new File(new File(Minecraft.getInstance().gameDirectory, "config"), "tooltipfix-toast.txt");
            mode = readMode();
            modeStamp = stamp();
        }
        if (++tick % 20 == 0) {
            long now = stamp();
            if (now != modeStamp) {
                modeStamp = now;
                mode = readMode();
            }
        }
        return mode;
    }

    public static void reset() {
        CACHE.clear();
        SPRITES.clear();
        modeFile = null;
        mode = null;
        modeStamp = Long.MIN_VALUE;
    }

    /** Replaces vanilla's single {@code blitSprite} call for the toast background. */
    public static void draw(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite,
                            int x, int y, int w, int h) {
        String m = mode();
        Slice s = "off".equals(m) ? null : slice(sprite);
        if (s == null || "clamp".equals(m)) {
            g.blitSprite(pipeline, sprite, x, y, w, h);
            return;
        }

        TextureAtlasSprite tas = atlasSprite(g, sprite);
        if (tas == null) {
            g.blitSprite(pipeline, sprite, x, y, w, h);
            return;
        }
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(tas.atlasLocation());
        GpuTextureView view = tex == null ? null : tex.getTextureView();
        GpuSampler sampler = tex == null ? null : tex.getSampler();
        if (view == null || sampler == null) {
            g.blitSprite(pipeline, sprite, x, y, w, h);
            return;
        }

        int bu = Math.round(s.left());
        int bv = Math.round(s.top());
        int br = Math.round(s.right());
        int bb = Math.round(s.bottom());

        float k = 1.0F;
        if (bu + br > w && bu + br > 0) {
            k = Math.min(k, (float) w / (float) (bu + br));
        }
        if (bv + bb > h && bv + bb > 0) {
            k = Math.min(k, (float) h / (float) (bv + bb));
        }
        int l = Math.round(bu * k);
        int r = Math.round(br * k);
        int t = Math.round(bv * k);
        int b = Math.round(bb * k);

        int midW = w - l - r;
        int midH = h - t - b;
        int srcMidW = s.baseW() - bu - br;
        int srcMidH = s.baseH() - bv - bb;
        int sw = s.baseW();
        int sh = s.baseH();

        part(g, tas, view, sampler, sw, sh, 0, 0, bu, bv, x, y, l, t);
        part(g, tas, view, sampler, sw, sh, sw - br, 0, br, bv, x + w - r, y, r, t);
        part(g, tas, view, sampler, sw, sh, 0, sh - bb, bu, bb, x, y + h - b, l, b);
        part(g, tas, view, sampler, sw, sh, sw - br, sh - bb, br, bb, x + w - r, y + h - b, r, b);

        if (midW > 0 && srcMidW > 0) {
            part(g, tas, view, sampler, sw, sh, bu, 0, srcMidW, bv, x + l, y, midW, t);
            part(g, tas, view, sampler, sw, sh, bu, sh - bb, srcMidW, bb, x + l, y + h - b, midW, b);
        }
        if (midH > 0 && srcMidH > 0) {
            part(g, tas, view, sampler, sw, sh, 0, bv, bu, srcMidH, x, y + t, l, midH);
            part(g, tas, view, sampler, sw, sh, sw - br, bv, br, srcMidH, x + w - r, y + t, r, midH);
        }
        if (midW > 0 && midH > 0 && srcMidW > 0 && srcMidH > 0) {
            part(g, tas, view, sampler, sw, sh, bu, bv, srcMidW, srcMidH, x + l, y + t, midW, midH);
        }
    }

    private static void part(GuiGraphicsExtractor g, TextureAtlasSprite tas, GpuTextureView view,
                             GpuSampler sampler, int sw, int sh, int su, int sv, int suw, int svh,
                             int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || suw <= 0 || svh <= 0) {
            return;
        }
        float u0 = tas.getU((float) su / (float) sw);
        float u1 = tas.getU((float) (su + suw) / (float) sw);
        float v0 = tas.getV((float) sv / (float) sh);
        float v1 = tas.getV((float) (sv + svh) / (float) sh);
        g.blit(view, sampler, x, y, x + w, y + h, u0, u1, v0, v1);
    }

    private static TextureAtlasSprite atlasSprite(GuiGraphicsExtractor g, Identifier id) {
        String key = id.toString();
        if (SPRITES.containsKey(key)) {
            return SPRITES.get(key);
        }
        TextureAtlasSprite tas;
        try {
            TextureAtlas atlas = ((GuiGraphicsExtractorAccess) (Object) g).tooltipfix$guiSprites();
            tas = atlas == null ? null : atlas.getSprite(id);
        } catch (Exception e) {
            LOG.warn("sprite lookup failed for {}: {}", id, e.toString());
            tas = null;
        }
        SPRITES.put(key, tas);
        return tas;
    }

    private static String readMode() {
        try {
            File f = modeFile;
            if (f == null || !f.isFile()) {
                return "fix";
            }
            String v = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("clamp")) {
                return v.toLowerCase();
            }
            return "fix";
        } catch (Exception e) {
            return "fix";
        }
    }

    private static long stamp() {
        File f = modeFile;
        return f != null && f.isFile() ? f.lastModified() * 31 + f.length() : -1L;
    }

    private static Slice slice(Identifier sprite) {
        String key = sprite.toString();
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        Slice s = load(sprite);
        CACHE.put(key, s);
        return s;
    }

    private static Slice load(Identifier sprite) {
        Identifier meta = Identifier.fromNamespaceAndPath(sprite.getNamespace(),
                "textures/gui/sprites/" + sprite.getPath() + ".png.mcmeta");
        Optional<Resource> found = Minecraft.getInstance().getResourceManager().getResource(meta);
        if (found.isEmpty()) {
            return null;
        }
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
