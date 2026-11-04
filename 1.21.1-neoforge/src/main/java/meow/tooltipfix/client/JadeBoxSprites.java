package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snownee.jade.api.theme.Theme;
import snownee.jade.api.ui.BoxStyle;

/**
 * Gives Jade's own tooltip box the sprite based style a modern resource pack ships.
 *
 * <p>1.21.11 (and every Jade from that version on) defines its built-in themes through
 * {@code tooltipStyle: {"version": 200, "sprite": "jade:<theme>_background"}}. That is what makes
 * {@code CozyUI} able to restyle the box at all: the pack only ships
 * {@code assets/jade/textures/gui/sprites/tooltip/<theme>_background.png} and never a theme json.
 *
 * <p>1.21.1 Jade (15.10.x) still declares every built-in theme as
 * {@code {"version": 100, "tooltipStyle": {"backgroundColor": ..., "borderColor": ...}}} - pure
 * colours - and its theme loader rejects any {@code version} outside 100..199, so the pack's
 * textures are never read even though this version already contains the machinery for them
 * ({@link BoxStyle.SpriteBase} plus nine-slice rendering straight from the sprite's {@code .mcmeta}).
 *
 * <p>So instead of painting the box ourselves we flip Jade into the mode it already supports: when
 * {@code jade:tooltip/<theme>_background} is on the GUI atlas, the active theme's style is swapped
 * for {@link BoxStyle#getSprite}. Jade then draws the box with exactly the code 1.21.11 uses, which
 * keeps nine-slice borders, {@code stretch_inner}, opacity and theme switching correct for free. If
 * the sprite is absent the original colour style is restored, so nothing changes for packs that do
 * not ship one.
 */
public final class JadeBoxSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    /**
     * Extra space on every side, matching the 9px the 1.21.11 renderer adds around the element box so
     * the sprite's decorative border sits outside the text.
     */
    private static final int OVERHANG = 9;

    /** How long to wait before re-checking the sprite, so a resource reload is noticed but frames stay cheap. */
    private static final long CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private static Theme lastTheme;
    private static long nextCheck;
    private static BoxStyle originalStyle;
    private static ResourceLocation appliedSprite;

    private JadeBoxSprites() {
    }

    /** {@code jade:dark} maps to {@code jade:tooltip/dark_background}, the id Jade 1.21.11 uses. */
    public static ResourceLocation spriteOf(ResourceLocation themeId) {
        return ResourceLocation.fromNamespaceAndPath(
                themeId.getNamespace(), "tooltip/" + themeId.getPath() + "_background");
    }

    /**
     * Called with every {@code Theme} Jade returns; cheap after the first decision for a theme.
     */
    public static void apply(Theme theme) {
        if (theme == null || theme.id == null) {
            return;
        }

        long now = System.nanoTime();
        if (theme == lastTheme && now < nextCheck) {
            return;
        }
        lastTheme = theme;
        nextCheck = now + CHECK_INTERVAL_NANOS;

        ResourceLocation sprite = spriteOf(theme.id);
        boolean available = isAvailable(sprite);

        if (available) {
            if (theme.tooltipStyle instanceof BoxStyle.SpriteBase) {
                // Already sprite based: either we swapped it earlier or the theme declared a sprite
                // itself (a pack is free to ship its own theme json).
                return;
            }
            originalStyle = theme.tooltipStyle;
            appliedSprite = sprite;
            theme.tooltipStyle = BoxStyle.getSprite(sprite, pad(theme.tooltipStyle == null ? null : theme.tooltipStyle.padding));
            LOGGER.info("[tooltipfix] jade theme {} uses sprite tooltip box {}", theme.id, sprite);
            return;
        }

        if (appliedSprite != null && theme.tooltipStyle instanceof BoxStyle.SpriteBase) {
            theme.tooltipStyle = originalStyle;
            LOGGER.info("[tooltipfix] jade theme {} fell back to the colour box (no {})", theme.id, appliedSprite);
        }
        appliedSprite = null;
        originalStyle = null;
    }

    private static int[] pad(int[] padding) {
        if (padding == null) {
            return null;
        }
        int[] padded = new int[padding.length];
        for (int i = 0; i < padding.length; i++) {
            padded[i] = padding[i] + OVERHANG;
        }
        return padded;
    }

    private static boolean isAvailable(ResourceLocation spriteId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getGuiSprites() == null) {
            return false;
        }

        TextureAtlasSprite sprite = minecraft.getGuiSprites().getSprite(spriteId);
        if (sprite == null) {
            return false;
        }

        // TextureAtlas#getSprite hands back the missingno sprite for unknown ids.
        return !MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name());
    }
}
