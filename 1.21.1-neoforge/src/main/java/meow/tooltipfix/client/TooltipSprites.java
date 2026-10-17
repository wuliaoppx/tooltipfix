package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.21.2 tooltip box renderer, lifted from that version's {@code TooltipRenderUtil}.
 *
 * <p>1.21.1 draws the box with {@code fill}/{@code fillGradient} using four colour constants; 1.21.2
 * blits two nine-slice GUI sprites instead, which is what lets a resource pack restyle the box by
 * dropping {@code assets/minecraft/textures/gui/sprites/tooltip/background.png} and {@code frame.png}
 * (plus their {@code .mcmeta}) into the pack.
 *
 * <p>The offsets are 1.21.2's exactly: the box keeps its 3px text padding, and the sprites are given
 * 9px of extra room on every side so their decorative border can sit outside the text.
 */
public final class TooltipSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    private static final int PADDING = 3;
    private static final int SPRITE_OVERHANG = 9;

    /** Same ids 1.21.2 ships in {@code assets/minecraft}. */
    public static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("tooltip/background");
    public static final ResourceLocation FRAME = ResourceLocation.withDefaultNamespace("tooltip/frame");

    private TooltipSprites() {
    }

    private static boolean loggedOnce = false;
    private static boolean lastAvailable = false;

    /**
     * Draws the sprite based tooltip box.
     *
     * @return false when the sprites are not on the GUI atlas (no pack and no bundled fallback),
     *         in which case the caller must keep the stock 1.21.1 colour rendering.
     */
    public static boolean render(GuiGraphics graphics, int x, int y, int width, int height, int z) {
        boolean available = isAvailable(BACKGROUND) && isAvailable(FRAME);
        if (!loggedOnce || available != lastAvailable) {
            loggedOnce = true;
            lastAvailable = available;
            LOGGER.info("[tooltipfix] sprite tooltip box {}", available
                    ? "ACTIVE (minecraft:tooltip/background + minecraft:tooltip/frame)"
                    : "inactive, falling back to the stock 1.21.1 colour box");
        }

        if (!available) {
            return false;
        }

        int left = x - PADDING - SPRITE_OVERHANG;
        int top = y - PADDING - SPRITE_OVERHANG;
        int spriteWidth = width + PADDING * 2 + SPRITE_OVERHANG * 2;
        int spriteHeight = height + PADDING * 2 + SPRITE_OVERHANG * 2;

        // The sprite path in 1.21.1 inherits GL blending from its call site; make it explicit so the box
        // is composited like 1.21.2 does (its RenderPipeline bakes the blend mode in).
        BlendGuard.begin("tooltip box");
        graphics.blitSprite(BACKGROUND, left, top, z, spriteWidth, spriteHeight);
        graphics.blitSprite(FRAME, left, top, z, spriteWidth, spriteHeight);
        BlendGuard.end();
        return true;
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
