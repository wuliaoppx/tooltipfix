package meow.tooltipfix.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.20.1 backport of the 1.21.2 sprite based tooltip box.
 *
 * <p>1.20.1 predates the whole GUI sprite pipeline (added in 1.20.2), so there is nothing to hook:
 * this class loads the two textures as plain standalone textures, reads their {@code .mcmeta}
 * nine-slice description itself and draws the box. The 1.21.2 geometry is reused verbatim - the text
 * keeps its 3px padding and the sprites get 9px of extra room on every side, so the box is drawn at
 * {@code (x-12, y-12)} sized {@code (width+24, height+24)}.
 *
 * <p>Tiling / stretching matters: vanilla 1.20.1 only knows how to tile the inner band, while 1.21.2
 * added {@code "stretch_inner": true} to stretch it instead. {@code GuiGraphics.blit} has an overload
 * where the source extent and the destination extent are separate arguments, which is exactly what
 * both modes need.
 */
public final class TooltipSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    public static final ResourceLocation BACKGROUND =
            new ResourceLocation("minecraft", "textures/gui/sprites/tooltip/background.png");
    public static final ResourceLocation FRAME =
            new ResourceLocation("minecraft", "textures/gui/sprites/tooltip/frame.png");

    private static final int PADDING = 3;
    private static final int SPRITE_OVERHANG = 9;

    private static final Map<ResourceLocation, SpriteInfo> CACHE = new HashMap<>();

    private static boolean loggedOnce = false;
    private static boolean lastAvailable = false;

    private TooltipSprites() {
    }

    /** @return false 时调用方不要 cancel，让原版颜色绘制继续跑。 */
    public static boolean render(GuiGraphics graphics, int x, int y, int width, int height, int z) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        boolean available = exists(resources, BACKGROUND) && exists(resources, FRAME);
        if (!loggedOnce || available != lastAvailable) {
            loggedOnce = true;
            lastAvailable = available;
            String detail = available
                    ? "ACTIVE background=" + packOf(resources, BACKGROUND) + " frame=" + packOf(resources, FRAME)
                    : "inactive: no pack provides textures/gui/sprites/tooltip/{background,frame}.png";
            LOGGER.info("[tooltipfix] sprite tooltip box {}", detail);
            writeDiagnostic("state: " + detail);
        }
        if (!available) {
            return false;
        }

        // 见 GuiSprite#ensureOwnTexture：GeckoLib 的 TextureManagerMixin 会劫持 TextureManager 里
        // 「还没注册」的贴图，动画图只上传第 0 帧；这两张 tooltip 图也按「帧条 + 九宫格」画，
        // 必须先自己注册，否则同样会被画成竖拉伸 + 常驻第 0 帧。
        GuiSprite.ensureOwnTexture(BACKGROUND);
        GuiSprite.ensureOwnTexture(FRAME);

        int left = x - PADDING - SPRITE_OVERHANG;
        int top = y - PADDING - SPRITE_OVERHANG;
        int boxWidth = width + PADDING * 2 + SPRITE_OVERHANG * 2;
        int boxHeight = height + PADDING * 2 + SPRITE_OVERHANG * 2;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, (float) z);
        draw(graphics, resources, BACKGROUND, left, top, boxWidth, boxHeight);
        draw(graphics, resources, FRAME, left, top, boxWidth, boxHeight);
        graphics.pose().popPose();
        return true;
    }

    private static boolean exists(ResourceManager resources, ResourceLocation texture) {
        return !resources.getResourceStack(texture).isEmpty();
    }

    /** 哪个材质包提供了这张图（诊断用，没找到就 <none>）。 */
    private static String packOf(ResourceManager resources, ResourceLocation texture) {
        List<Resource> stack = resources.getResourceStack(texture);
        return stack.isEmpty() ? "<none>" : stack.get(0).sourcePackId();
    }

    /**
     * 把上一次自检结果写到游戏目录下的 tooltipfix-status.txt。
     *
     * <p>纯诊断用途：Forge 端"看起来完全没生效"时，看这一行就能区分「mixin 根本没跑」和
     * 「跑了但没找到贴图」，比翻几千行 latest.log 快。正常运行时只在状态变化时覆写一次。
     */
    private static void writeDiagnostic(String state) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameDirectory == null) {
                return;
            }
            File file = new File(minecraft.gameDirectory, "tooltipfix-status.txt");
            Files.writeString(file.toPath(),
                    "tooltipfix (1.20.1)\n" + state + "\nlastTooltipRender=" + Util.getMillis() + "\n",
                    StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 诊断失败绝不影响渲染
        }
    }

    private static void draw(GuiGraphics graphics, ResourceManager resources, ResourceLocation texture,
                             int x, int y, int width, int height) {
        SpriteInfo info = info(resources, texture);
        if (info == null || info.left <= 0 || info.top <= 0 || info.right <= 0 || info.bottom <= 0) {
            // 没有（或没有九宫格信息的）mcmeta：整张图当一格拉伸
            graphics.blit(texture, x, y, width, height, 0.0F, 0.0F, 1, 1, 1, 1);
            return;
        }

        int left = Math.min(info.left, width / 2);
        int right = Math.min(info.right, width / 2);
        int top = Math.min(info.top, height / 2);
        int bottom = Math.min(info.bottom, height / 2);

        int sheetWidth = info.width;
        int sheetHeight = info.height;
        // 源内区尺寸与目标内区尺寸必须分开算（1.21.1 那版就是混用才出的贴图错位）
        int bandWidth = sheetWidth - left - right;
        int bandHeight = sheetHeight - top - bottom;
        int innerWidth = width - left - right;
        int innerHeight = height - top - bottom;

        // 帧条：整张图有 info.sheetFrames 帧，取当前帧的逻辑 v 起点
        int texWidth = sheetWidth;
        int texHeight = sheetHeight * info.sheetFrames;
        float vBase = (float) (currentFrame(info) * sheetHeight);

        int rightX = x + width - right;
        int bottomY = y + height - bottom;

        // 四个角（1:1）
        blit(graphics, texture, x, y, left, top, 0, vBase, left, top, texWidth, texHeight);
        blit(graphics, texture, rightX, y, right, top, sheetWidth - right, vBase, right, top, texWidth, texHeight);
        blit(graphics, texture, x, bottomY, left, bottom,
                0, vBase + sheetHeight - bottom, left, bottom, texWidth, texHeight);
        blit(graphics, texture, rightX, bottomY, right, bottom,
                sheetWidth - right, vBase + sheetHeight - bottom, right, bottom, texWidth, texHeight);

        if (info.stretchInner) {
            blit(graphics, texture, x + left, y, innerWidth, top,
                    left, vBase, bandWidth, top, texWidth, texHeight);
            blit(graphics, texture, x + left, bottomY, innerWidth, bottom,
                    left, vBase + sheetHeight - bottom, bandWidth, bottom, texWidth, texHeight);
            blit(graphics, texture, x, y + top, left, innerHeight,
                    0, vBase + top, left, bandHeight, texWidth, texHeight);
            blit(graphics, texture, rightX, y + top, right, innerHeight,
                    sheetWidth - right, vBase + top, right, bandHeight, texWidth, texHeight);
            blit(graphics, texture, x + left, y + top, innerWidth, innerHeight,
                    left, vBase + top, bandWidth, bandHeight, texWidth, texHeight);
        } else {
            // 内区平铺（1.20.1 原版语义）：每块 1:1，最后一块按剩余量截短源区
            tile(graphics, texture, x + left, y, innerWidth, top,
                    left, vBase, bandWidth, top, texWidth, texHeight, true);
            tile(graphics, texture, x + left, bottomY, innerWidth, bottom,
                    left, vBase + sheetHeight - bottom, bandWidth, bottom, texWidth, texHeight, true);
            tile(graphics, texture, x, y + top, left, innerHeight,
                    0, vBase + top, left, bandHeight, texWidth, texHeight, false);
            tile(graphics, texture, rightX, y + top, right, innerHeight,
                    sheetWidth - right, vBase + top, right, bandHeight, texWidth, texHeight, false);
            tile(graphics, texture, x + left, y + top, innerWidth, innerHeight,
                    left, vBase + top, bandWidth, bandHeight, texWidth, texHeight, true);
        }
    }

    /** 沿一个轴（innerWidth/innerHeight 用 horizontal 选择）平铺。 */
    private static void tile(GuiGraphics graphics, ResourceLocation texture, int x, int y,
                             int destW, int destH, int srcX, float srcY, int tileW, int tileH,
                             int texWidth, int texHeight, boolean horizontal) {
        int span = horizontal ? destW : destH;
        int tile = horizontal ? tileW : tileH;
        if (span <= 0 || tile <= 0) {
            return;
        }
        for (int offset = 0; offset < span; offset += tile) {
            int size = Math.min(tile, span - offset);
            if (horizontal) {
                blit(graphics, texture, x + offset, y, size, destH,
                        srcX, srcY, size, tileH, texWidth, texHeight);
            } else {
                blit(graphics, texture, x, y + offset, destW, size,
                        srcX, srcY, tileW, size, texWidth, texHeight);
            }
        }
    }

    private static void blit(GuiGraphics graphics, ResourceLocation texture,
                             int x, int y, int destW, int destH,
                             int u, float v, int srcW, int srcH, int texWidth, int texHeight) {
        if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) {
            return;
        }
        graphics.blit(texture, x, y, destW, destH, (float) u, v, srcW, srcH, texWidth, texHeight);
    }

    private static int currentFrame(SpriteInfo info) {
        if (info.sheetFrames <= 1 || info.frameOrder.length <= 1 || info.frameTimeMs <= 0) {
            return 0;
        }
        long tick = Util.getMillis() / (long) info.frameTimeMs;
        int index = (int) (tick % (long) info.frameOrder.length);
        if (index < 0) {
            index += info.frameOrder.length;
        }
        int frame = info.frameOrder[index];
        return frame >= 0 && frame < info.sheetFrames ? frame : 0;
    }

    // ------------------------------------------------------------------ mcmeta

    private static SpriteInfo info(ResourceManager resources, ResourceLocation texture) {
        ResourceLocation meta = new ResourceLocation(texture.getNamespace(), texture.getPath() + ".mcmeta");
        List<Resource> stack = resources.getResourceStack(meta);
        if (stack.isEmpty()) {
            CACHE.remove(texture);
            return null;
        }
        Resource resource = stack.get(0);
        // The image and its .mcmeta are resolved independently, so log both packs: if they ever
        // disagree the U/V math (which comes from the metadata) would not match the drawn image.
        List<Resource> images = resources.getResourceStack(texture);
        String imagePack = images.isEmpty() ? "<none>" : images.get(0).sourcePackId();
        String packId = resource.sourcePackId() + " | image from " + imagePack;
        SpriteInfo cached = CACHE.get(texture);
        if (cached != null && packId.equals(cached.packId)) {
            return cached;
        }

        SpriteInfo parsed = parse(resource, packId);
        CACHE.put(texture, parsed);
        LOGGER.info("[tooltipfix] {} -> nine_slice {}x{} border {},{},{},{} stretch_inner={} frames={} (mcmeta {} | image {})",
                texture, parsed.width, parsed.height, parsed.left, parsed.top, parsed.right, parsed.bottom,
                parsed.stretchInner, parsed.sheetFrames, resource.sourcePackId(), imagePack);
        return parsed;
    }

    private static SpriteInfo parse(Resource resource, String packId) {
        try (BufferedReader reader = resource.openAsReader()) {
            // 与 GsonHelper 一致：vanilla 读 mcmeta 用的是 lenient reader，能接受
// 材质包里常见的 // 注释（例：CozyUI 的 widget/button_highlighted.png.mcmeta）。
            JsonReader jsonReader = new JsonReader(reader);
            jsonReader.setLenient(true);
            JsonObject root = JsonParser.parseReader(jsonReader).getAsJsonObject();

            int width = 0;
            int height = 0;
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;
            boolean stretchInner = false;

            JsonObject gui = root.has("gui") ? root.getAsJsonObject("gui") : null;
            JsonObject scaling = gui != null && gui.has("scaling") ? gui.getAsJsonObject("scaling") : null;
            if (scaling != null) {
                String type = scaling.has("type") ? scaling.get("type").getAsString() : "stretch";
                width = intOr(scaling, "width", 0);
                height = intOr(scaling, "height", 0);
                JsonElement border = scaling.get("border");
                if (border != null && border.isJsonObject()) {
                    JsonObject o = border.getAsJsonObject();
                    left = intOr(o, "left", 0);
                    top = intOr(o, "top", 0);
                    right = intOr(o, "right", 0);
                    bottom = intOr(o, "bottom", 0);
                } else if (border != null && border.isJsonPrimitive()) {
                    left = top = right = bottom = border.getAsInt();
                }
                stretchInner = scaling.has("stretch_inner") && scaling.get("stretch_inner").getAsBoolean();
                if (!"nine_slice".equals(type)) {
                    // stretch / tile 都没有九宫格信息，退化成整图
                    left = top = right = bottom = 0;
                }
            }

            int sheetFrames = 1;
            int[] frameOrder = new int[]{0};
            int frameTimeMs = -1;
            JsonObject animation = root.has("animation") ? root.getAsJsonObject("animation") : null;
            if (animation != null) {
                frameTimeMs = intOr(animation, "frametime", 1) * 50;
                JsonArray frames = animation.has("frames") ? animation.getAsJsonArray("frames") : null;
                if (frames != null && frames.size() > 0) {
                    frameOrder = new int[frames.size()];
                    int max = 0;
                    for (int i = 0; i < frames.size(); i++) {
                        JsonElement e = frames.get(i);
                        // 帧项可以是整数，也可以是 {"index":n,"time":t} 形式
                        int value = e.isJsonObject() ? intOr(e.getAsJsonObject(), "index", 0) : e.getAsInt();
                        frameOrder[i] = value;
                        max = Math.max(max, value);
                    }
                    sheetFrames = max + 1;
                }
            }

            return new SpriteInfo(packId, width, height, left, top, right, bottom, stretchInner,
                    sheetFrames, frameOrder, frameTimeMs);
        } catch (Exception e) {
            LOGGER.warn("[tooltipfix] could not parse the nine-slice metadata of {}", resource, e);
            return new SpriteInfo(packId, 0, 0, 0, 0, 0, 0, false, 1, new int[]{0}, -1);
        }
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    private static final class SpriteInfo {
        final String packId;
        final int width;
        final int height;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final boolean stretchInner;
        final int sheetFrames;
        final int[] frameOrder;
        final int frameTimeMs;

        SpriteInfo(String packId, int width, int height, int left, int top, int right, int bottom,
                   boolean stretchInner, int sheetFrames, int[] frameOrder, int frameTimeMs) {
            this.packId = packId;
            this.width = width;
            this.height = height;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.stretchInner = stretchInner;
            this.sheetFrames = sheetFrames;
            this.frameOrder = frameOrder;
            this.frameTimeMs = frameTimeMs;
        }
    }
}
