package meow.tooltipfix.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.theme.Theme;
import snownee.jade.api.ui.BoxStyle;

import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;

/** Backports the Jade skin at draw time without mutating the theme or the root element. */
public final class JadeBoxSprites {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix");
    private static final Map<TextureAtlasSprite, Insets> INSETS = new WeakHashMap<>();
    private static final Insets NONE = new Insets(0, 0, 0, 0);
    private static ResourceLocation lastDrawn;

    private JadeBoxSprites() {
    }

    public static boolean draw(BoxStyle style, GuiGraphics graphics, float x, float y, float w, float h, float alpha) {
        Theme theme = IThemeHelper.get().theme();
        if (theme == null || theme.id == null || style != theme.tooltipStyle
                || style instanceof BoxStyle.SpriteBase || w <= 0 || h <= 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation background = sprite(theme.id, "_background");
        ResourceLocation frame = sprite(theme.id, "_frame");
        TextureAtlasSprite bg = find(mc, background);
        TextureAtlasSprite fg = find(mc, frame);
        if (bg == null && fg == null) {
            if (lastDrawn != null) {
                LOG.info("[tooltipfix] jade box reverted to its stock style");
                lastDrawn = null;
            }
            return false;
        }
        if (!background.equals(lastDrawn)) {
            LOG.info("[tooltipfix] jade box uses pack sprites for {}", theme.id);
            if (Boolean.getBoolean("tooltipfix.selftest.jade")) LOG.info("[tooltipfix] jade paint rect={},{} {}x{} alpha={} pose={},{}", x, y, w, h, alpha, graphics.pose().last().pose().m30(), graphics.pose().last().pose().m31());
            lastDrawn = background;
        }
        int left = Math.round(x);
        int top = Math.round(y);
        int width = Math.round(x + w) - left;
        int height = Math.round(y + h) - top;
        // Jade already reserves a one-unit outer border for its rounded colour style.
        // Preserve that paint extent without altering padding, the theme, or progress geometry.
        if (style.hasRoundCorner()) { left--; top--; width += 2; height += 2; }
        // The transparent canvas is not part of Jade's layout. Give only the paint extra room.
        TextureAtlasSprite reference = bg == null ? fg : bg;
        Insets insets = INSETS.computeIfAbsent(reference, JadeBoxSprites::measure)
                .fitting(width, height, mc.getGuiSprites().getSpriteScaling(reference));
        graphics.setColor(1, 1, 1, alpha);
        BlendGuard.begin("jade box");
        try {
            if (bg != null) draw(graphics, background, left, top, width, height, insets);
            if (fg != null) draw(graphics, frame, left, top, width, height, insets);
        } finally {
            BlendGuard.end();
            graphics.setColor(1, 1, 1, 1);
        }
        return true;
    }

    private static void draw(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int w, int h, Insets i) {
        graphics.blitSprite(sprite, x - i.left, y - i.top, w + i.left + i.right, h + i.top + i.bottom);
    }

    private static ResourceLocation sprite(ResourceLocation theme, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(theme.getNamespace(), "tooltip/" + theme.getPath() + suffix);
    }

    private static TextureAtlasSprite find(Minecraft mc, ResourceLocation id) {
        TextureAtlasSprite sprite = mc.getGuiSprites().getSprite(id);
        return sprite != null && id.equals(sprite.contents().name()) ? sprite : null;
    }

    private static Insets measure(TextureAtlasSprite sprite) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getGuiSprites().getSpriteScaling(sprite) instanceof GuiSpriteScaling.NineSlice slice)) return NONE;
        ResourceLocation id = sprite.contents().name();
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/gui/sprites/" + id.getPath() + ".png");
        var resource = mc.getResourceManager().getResource(path);
        if (resource.isEmpty()) return NONE;
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            int iw = image.getWidth(), ih = image.getHeight();
            if (iw != sprite.contents().width() || ih != sprite.contents().height()) return NONE;
            int l = iw, t = ih, r = -1, b = -1;
            for (int py = 0; py < ih; py++) {
                for (int px = 0; px < iw; px++) {
                    if ((image.getPixelRGBA(px, py) >>> 24) != 0) {
                        l = Math.min(l, px); t = Math.min(t, py);
                        r = Math.max(r, px); b = Math.max(b, py);
                    }
                }
            }
            if (r < l || b < t) return NONE;
            Insets insets = new Insets(l * slice.width() / iw, t * slice.height() / ih,
                    (iw - 1 - r) * slice.width() / iw, (ih - 1 - b) * slice.height() / ih);
            LOG.info("[tooltipfix] jade background {} from {}: transparent insets {}", id, resource.get().sourcePackId(), insets);
            return insets;
        } catch (Exception ex) {
            LOG.warn("[tooltipfix] could not measure Jade sprite {}", id, ex);
            return NONE;
        }
    }

    private record Insets(int left, int top, int right, int bottom) {
        Insets fitting(int w, int h, GuiSpriteScaling scaling) {
            if (!(scaling instanceof GuiSpriteScaling.NineSlice slice)) return NONE;
            var b = slice.border();
            // Only compensate vacant pixels that remain in the unscaled border bands.
            int l = Math.min(left, b.left()), r = Math.min(right, b.right());
            int t = Math.min(top, b.top()), d = Math.min(bottom, b.bottom());
            if (Math.abs(l - r) > w) l = r = 0;
            if (Math.abs(t - d) > h) t = d = 0;
            return new Insets(l, t, r, d);
        }
    }
}
