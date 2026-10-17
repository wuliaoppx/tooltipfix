package meow.tooltipfix.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.BufferedReader;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import meow.tooltipfix.mixin.GuiGraphicsInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backport of the 1.21.2 nine-slice {@code stretch_inner} option.
 *
 * <p>1.21.2 added {@code "stretch_inner": true} to the {@code gui.scaling} section of a sprite
 * {@code .mcmeta}. When it is set, the centre region of a nine-slice sprite is drawn as one
 * stretched quad instead of being tiled. 1.21.1 silently drops the unknown key (DFU ignores extra
 * fields) and therefore always tiles, which breaks every pack authored for 1.21.2+.
 *
 * <p>The geometry comes from the vanilla {@link GuiSpriteScaling.NineSlice} - only the boolean is
 * missing, and that is read straight from the winning {@code .png.mcmeta} in the resource manager.
 * The result is memoised per {@link TextureAtlasSprite} instance; the atlas creates fresh sprites
 * on every resource reload, so a weak map self-invalidates.
 */
public final class GuiSpriteStretching {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    /** Sprite ids are atlas-local, so the texture path has to be reconstructed. */
    private static final String[] TEXTURE_PATH_PREFIXES = {"textures/gui/sprites/", "textures/"};

    private static final Map<TextureAtlasSprite, Boolean> STRETCH_INNER =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean loggedFirstStretch = false;

    private GuiSpriteStretching() {
    }

    /**
     * @return true when the sprite's {@code .mcmeta} opted into {@code stretch_inner}, in which case
     *         the caller must render the sprite itself with {@link #blitNineSliceStretched}.
     */
    public static boolean stretchInner(TextureAtlasSprite sprite) {
        Boolean cached = STRETCH_INNER.get(sprite);
        if (cached != null) {
            return cached;
        }

        boolean value = readStretchInner(sprite);
        STRETCH_INNER.put(sprite, value);

        if (value) {
            if (!loggedFirstStretch) {
                loggedFirstStretch = true;
                LOGGER.info("[tooltipfix] nine-slice stretch_inner support active");
            }
            LOGGER.debug("[tooltipfix] {} requests stretch_inner", sprite.contents().name());
        }

        return value;
    }

    private static boolean readStretchInner(TextureAtlasSprite sprite) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return false;
        }

        try {
            // SpriteContents#name is the sprite id (e.g. "minecraft:widget/button"), not the file.
            ResourceLocation spriteId = sprite.contents().name();
            for (String prefix : TEXTURE_PATH_PREFIXES) {
                ResourceLocation metaLocation = ResourceLocation.fromNamespaceAndPath(
                        spriteId.getNamespace(), prefix + spriteId.getPath() + ".png.mcmeta");
                Optional<Resource> resource = minecraft.getResourceManager().getResource(metaLocation);
                if (resource.isPresent()) {
                    return parseStretchInner(resource.get());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[tooltipfix] could not read the nine-slice metadata of a GUI sprite", e);
        }

        return false;
    }

    private static boolean parseStretchInner(Resource resource) throws Exception {
        try (BufferedReader reader = resource.openAsReader()) {
            // 与 GsonHelper 一致：vanilla 读 mcmeta 用的是 lenient reader，能接受
// 材质包里常见的 // 注释（例：CozyUI 的 widget/button_highlighted.png.mcmeta）。
            JsonReader jsonReader = new JsonReader(reader);
            jsonReader.setLenient(true);
            JsonObject root = JsonParser.parseReader(jsonReader).getAsJsonObject();
            JsonObject gui = root.getAsJsonObject("gui");
            if (gui == null) {
                return false;
            }

            JsonObject scaling = gui.getAsJsonObject("scaling");
            if (scaling == null) {
                return false;
            }

            JsonElement stretchInner = scaling.get("stretch_inner");
            return stretchInner != null && stretchInner.isJsonPrimitive() && stretchInner.getAsBoolean();
        }
    }

    /**
     * Mirror of {@code GuiGraphics.blitNineSlicedSprite} with the inner columns/rows/centre drawn
     * stretched rather than tiled. The border clamping matches vanilla, so the nine regions always
     * tile the destination exactly.
     */
    public static void blitNineSliceStretched(
            GuiGraphics graphics,
            TextureAtlasSprite sprite,
            GuiSpriteScaling.NineSlice nineSlice,
            int x, int y, int z, int width, int height) {
        GuiSpriteScaling.NineSlice.Border border = nineSlice.border();
        int left = Math.min(border.left(), width / 2);
        int right = Math.min(border.right(), width / 2);
        int top = Math.min(border.top(), height / 2);
        int bottom = Math.min(border.bottom(), height / 2);

        int sheetWidth = nineSlice.width();
        int sheetHeight = nineSlice.height();

        // Source extent of the middle band. Vanilla repeats this band with blitTiledSprite;
        // stretch_inner keeps the same source rectangle but scales it to the destination instead of
        // repeating it - so source extent and destination extent must stay two separate values.
        // (Using the destination extent for the UV range walks past the sprite and samples whatever
        // neighbouring sprite happens to sit next to it in the atlas.)
        int bandWidth = sheetWidth - left - right;
        int bandHeight = sheetHeight - top - bottom;

        int innerWidth = width - left - right;
        int innerHeight = height - top - bottom;
        int rightX = x + width - right;
        int bottomY = y + height - bottom;

        // corners: unscaled, source extent == destination extent
        blit(graphics, sprite, sheetWidth, sheetHeight, 0, 0, left, top,
                x, y, z, left, top);
        blit(graphics, sprite, sheetWidth, sheetHeight, sheetWidth - right, 0, right, top,
                rightX, y, z, right, top);
        blit(graphics, sprite, sheetWidth, sheetHeight, 0, sheetHeight - bottom, left, bottom,
                x, bottomY, z, left, bottom);
        blit(graphics, sprite, sheetWidth, sheetHeight, sheetWidth - right, sheetHeight - bottom, right, bottom,
                rightX, bottomY, z, right, bottom);

        // edges: source keeps the band size, destination is stretched
        blit(graphics, sprite, sheetWidth, sheetHeight, left, 0, bandWidth, top,
                x + left, y, z, innerWidth, top);
        blit(graphics, sprite, sheetWidth, sheetHeight, left, sheetHeight - bottom, bandWidth, bottom,
                x + left, bottomY, z, innerWidth, bottom);
        blit(graphics, sprite, sheetWidth, sheetHeight, 0, top, left, bandHeight,
                x, y + top, z, left, innerHeight);
        blit(graphics, sprite, sheetWidth, sheetHeight, sheetWidth - right, top, right, bandHeight,
                rightX, y + top, z, right, innerHeight);

        // centre: stretched on both axes
        blit(graphics, sprite, sheetWidth, sheetHeight, left, top, bandWidth, bandHeight,
                x + left, y + top, z, innerWidth, innerHeight);
    }

    private static void blit(
            GuiGraphics graphics,
            TextureAtlasSprite sprite,
            int sheetWidth, int sheetHeight,
            int sourceX, int sourceY, int sourceWidth, int sourceHeight,
            int x, int y, int z, int width, int height) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        ((GuiGraphicsInvoker) (Object) graphics).tooltipfix$innerBlit(
                sprite.atlasLocation(),
                x, x + width,
                y, y + height,
                z,
                sprite.getU((float) sourceX / (float) sheetWidth),
                sprite.getU((float) (sourceX + sourceWidth) / (float) sheetWidth),
                sprite.getV((float) sourceY / (float) sheetHeight),
                sprite.getV((float) (sourceY + sourceHeight) / (float) sheetHeight));
    }
}
