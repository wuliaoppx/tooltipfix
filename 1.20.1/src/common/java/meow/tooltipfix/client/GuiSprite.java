package meow.tooltipfix.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.BufferedReader;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.20.1 用的「GUI sprite」实现：把材质包里的 {@code assets/minecraft/textures/gui/sprites/**.png}
 * 当作 1.20.2+ 那样的 sprite 来画。
 *
 * <p>1.20.1 完全没有 GUI sprite 管线（那是 1.20.2 才引入的），所以这里自己：
 * <ul>
 *   <li>读 {@code .png.mcmeta}：{@code gui.scaling}（stretch / nine_slice + border + stretch_inner）与
 *       {@code animation}（frametime / frames / 每帧逻辑尺寸）；解析用 lenient reader，跟 vanilla 一致，
 *       这样带 {@code //} 注释的 mcmeta（CozyUI 就有）也能读；</li>
 *   <li>自己按时间步进动画帧（{@code SimpleTexture} 不会给独立贴图播动画）；</li>
 *   <li>用 {@code GuiGraphics.blit} 的「源尺寸 / 目标尺寸分开」重载来画九宫格、拉伸或平铺。</li>
 * </ul>
 *
 * <p>逻辑尺寸（mcmeta 里写的 width/height）和实际 png 像素尺寸不需要一致：UV 全部按逻辑单位算，
 * 归一化时也除以逻辑尺寸，比例相同即可，GPU 会自己采到正确位置。
 */
public final class GuiSprite {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");
    private static final Map<ResourceLocation, GuiSprite> CACHE = new HashMap<>();
    /** 由我们自己注册进 TextureManager 的贴图对象，见 {@link #ensureOwnTexture(ResourceLocation)}。 */
    private static final Map<ResourceLocation, SimpleTexture> OWN_TEXTURES = new HashMap<>();
    /** 诊断用：每个 sprite 只在第一次加载时打一行几何信息，方便核对材质包写法。 */
    private static final java.util.Set<String> LOGGED_LOAD = new java.util.HashSet<>();

    /**
     * 需要 GL_LINEAR 过滤的 sprite 贴图（只点这两张，理由见 {@link #applyLinearFilter}）。
     */
    private static final java.util.Set<ResourceLocation> LINEAR_TEXTURES = java.util.Set.of(
            textureOf(new ResourceLocation("minecraft", "container/slot_highlight_back")),
            textureOf(new ResourceLocation("minecraft", "container/slot_highlight_front")));

    /** GUI sprite 的贴图 id 前缀，和 1.21.2 里 {@code SpriteSourceList} 注册的路径一致。 */
    public static final String SPRITE_DIRECTORY = "textures/gui/sprites/";

    private final ResourceLocation texture;
    private final String packId;
    private final int frameWidth;
    private final int frameHeight;
    private final int frames;
    private final int[] frameOrder;
    private final int frameTimeMs;
    private final int borderLeft;
    private final int borderTop;
    private final int borderRight;
    private final int borderBottom;
    private final boolean nineSlice;
    private final boolean stretchInner;

    private GuiSprite(ResourceLocation texture, String packId, int frameWidth, int frameHeight, int frames,
                      int[] frameOrder, int frameTimeMs, int borderLeft, int borderTop, int borderRight,
                      int borderBottom, boolean nineSlice, boolean stretchInner) {
        this.texture = texture;
        this.packId = packId;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.frames = frames;
        this.frameOrder = frameOrder;
        this.frameTimeMs = frameTimeMs;
        this.borderLeft = borderLeft;
        this.borderTop = borderTop;
        this.borderRight = borderRight;
        this.borderBottom = borderBottom;
        this.nineSlice = nineSlice;
        this.stretchInner = stretchInner;
    }

    /** @return 没有这个 sprite（材质包没提供）时返回 null。 */
    public static GuiSprite of(ResourceLocation spriteId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return null;
        }
        ResourceManager resources = minecraft.getResourceManager();
        ResourceLocation texture = textureOf(spriteId);
        if (resources.getResourceStack(texture).isEmpty()) {
            return null;
        }
        GuiSprite cached = CACHE.get(spriteId);
        if (cached != null) {
            return cached;
        }
        GuiSprite loaded = load(resources, spriteId, texture);
        ensureOwnTexture(texture);
        CACHE.put(spriteId, loaded);
        if (LOGGED_LOAD.add(spriteId.toString())) {
            LOGGER.info("[tooltipfix] sprite {} pack='{}' {}", spriteId, loaded.packId(), loaded.describe());
        }
        return loaded;
    }

    public static void invalidateCache() {
        CACHE.clear();
        OWN_TEXTURES.clear();
    }

    /**
     * 自己把 sprite 贴图注册进 {@code TextureManager}，不走「让原版懒加载」的默认路径。
     *
     * <p>原因：GeckoLib 4.x 的 {@code TextureManagerMixin} 在
     * {@code TextureManager.getTexture(ResourceLocation)} 的 HEAD 插了一刀——凡是 map 里还没有的贴图
     * 路径，它就塞一个自己的 {@code AnimatableTexture} 进去；如果那张图带 {@code animation} 元数据，它
     * 就<b>留着不放</b>。而 {@code AnimatableTexture.load()} 只把动画的<b>单帧</b>上传成 GL 贴图
     * （{@code TextureUtil.prepareImage(id, 0, frameW, frameH)} + 只上传 frame 0），并且只有主动调用
     * {@code AnimatableTexture.setAndUpdate(...)} 的模组才会推进它的帧——我们不会，于是它永远停在第 0 帧。
     *
     * <p>我们的画法是把整条 PNG 当作「frames 帧、每帧 frameHeight 高」的条带，UV 归一化按
     * {@code frameHeight * frames} 除：贴图实际只有一帧那么高 ⇒ 采样被压在顶部 1/frames 的条上再铺满整个
     * 按钮，看上去就是「被竖向拉伸数倍」，而第 0 帧自带的白三角/白条则常驻（详见 GeckoLib 分析）。
     *
     * <p>先自行 {@code register} 一个 {@code SimpleTexture}（与 1.20.1 原版懒加载的结果等价，且会照常
     * 应用 {@code .mcmeta} 里的 texture 过滤设置），GeckoLib 看到 map 里已有条目就会跳过，条带就完整了。
     */
    public static void ensureOwnTexture(ResourceLocation texture) {
        if (OWN_TEXTURES.containsKey(texture)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        TextureManager manager = minecraft.getTextureManager();
        SimpleTexture own = new SimpleTexture(texture);
        manager.register(texture, own);
        OWN_TEXTURES.put(texture, own);
    }

    /**
     * 给 {@link #LINEAR_TEXTURES} 里点名的 sprite 设 GL_LINEAR。
     *
     * <p>为什么需要：这些 PNG 的分辨率是逻辑画布的整数倍（CozyUI 的 slot_highlight_* 是 24 逻辑画布配
     * 96x96 = 4 倍超采样）。GUI scale 不是 4 的整数倍时（用户整合包 guiScale=3 ⇒ 96 texel 要画到 72 个物理
     * 像素）GPU 得做 0.75 倍缩小，而 1.20.1 的 {@code TextureUtil.prepareImage} 把 GL_TEXTURE_MIN/MAG_FILTER
     * 固定成 GL_NEAREST ⇒ 每 4 个 texel 丢掉 1 个，圆角自带的抗锯齿像素被抽样掉，圆角变成台阶、描边忽粗忽细
     * （用户原话「框的形状很奇怪，而且有锯齿」）。4 倍超采样下线性过滤等价于正确的箱式降采样；而 GUI scale 4
     * （1:1）时线性与最近邻的采样结果完全相同，所以不会引入副作用。
     *
     * <p>为什么不全局做：1.0.13 的「全局超采样降采样 + 线性过滤」把暂停菜单按钮、紫色选中按钮、高亮框一起搞
     * 发虚，被用户判为「效果更差了」（原话）。视觉修正必须限定目标 sprite。
     *
     * <p>每帧调用一次只花 3 次 GL 调用；好处是资源重载（{@code prepareImage} 会把过滤重置回 NEAREST）或换材质包
     * 之后自动重新生效，不必另挂钩重载事件。
     */
    private static void applyLinearFilter(ResourceLocation texture) {
        SimpleTexture own = OWN_TEXTURES.get(texture);
        if (own == null) {
            return;
        }
        RenderSystem.bindTexture(own.getId());
        RenderSystem.texParameter(3553, 10241, 9729); // GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR
        RenderSystem.texParameter(3553, 10240, 9729); // GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR
    }

    /** sprite id（如 {@code minecraft:hud/hotbar}）→ 贴图路径。 */
    public static ResourceLocation textureOf(ResourceLocation spriteId) {
        return new ResourceLocation(spriteId.getNamespace(), SPRITE_DIRECTORY + spriteId.getPath() + ".png");
    }

    private static GuiSprite load(ResourceManager resources, ResourceLocation spriteId, ResourceLocation texture) {
        Resource png = resources.getResourceStack(texture).get(0);
        String packId = png.sourcePackId();
        int[] imageSize = readPngSize(resources, texture);
        int imageWidth = imageSize[0];
        int imageHeight = imageSize[1];

        int frameWidth = imageWidth;
        int frameHeight = imageHeight;
        int frames = 1;
        int[] frameOrder = new int[]{0};
        int frameTimeMs = -1;
        boolean nineSlice = false;
        boolean scalingSized = false;
        boolean stretchInner = false;
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;

        ResourceLocation metaId = new ResourceLocation(texture.getNamespace(), texture.getPath() + ".mcmeta");
        if (!resources.getResourceStack(metaId).isEmpty()) {
            try (BufferedReader reader = resources.getResourceStack(metaId).get(0).openAsReader()) {
                JsonReader jsonReader = new JsonReader(reader);
                jsonReader.setLenient(true);
                JsonObject root = JsonParser.parseReader(jsonReader).getAsJsonObject();

                JsonObject gui = root.has("gui") ? root.getAsJsonObject("gui") : null;
                JsonObject scaling = gui != null && gui.has("scaling") ? gui.getAsJsonObject("scaling") : null;
                if (scaling != null && "nine_slice".equals(stringOr(scaling, "type", "stretch"))) {
                    scalingSized = scaling.has("width") && scaling.has("height");
                    nineSlice = true;
                    stretchInner = scaling.has("stretch_inner") && scaling.get("stretch_inner").getAsBoolean();
                    left = top = right = bottom = 0;
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
                    if (scaling.has("width")) {
                        frameWidth = intOr(scaling, "width", frameWidth);
                    }
                    if (scaling.has("height")) {
                        frameHeight = intOr(scaling, "height", frameHeight);
                    }
                }

                JsonObject animation = root.has("animation") ? root.getAsJsonObject("animation") : null;
                if (animation != null) {
                    frameTimeMs = intOr(animation, "frametime", 1) * 50;
                    // animation.width/height 是「纹理像素」尺寸（CozyUI 的经验条这里是 728x20，
                    // 而 GUI 逻辑尺寸只有 182x5）。帧数只能用它算，不能拿它当绘制空间，
                    // 否则 gui.scaling 里的九宫格边距会全错。
                    int animWidth = intOr(animation, "width", 0);
                    int animHeight = intOr(animation, "height", 0);
                    if (!scalingSized && animWidth > 0 && animHeight > 0) {
                        frameWidth = animWidth;
                        frameHeight = animHeight;
                    }
                    int baseWidth = animWidth > 0 ? animWidth : frameWidth;
                    int baseHeight = animHeight > 0 ? animHeight : frameHeight;
                    int scale = baseWidth > 0 && imageWidth % baseWidth == 0 ? imageWidth / baseWidth : 1;
                    int realFrameHeight = baseHeight * scale;
                    if (realFrameHeight > 0) {
                        frames = Math.max(1, imageHeight / realFrameHeight);
                    }
                    JsonArray frameArray = animation.has("frames") ? animation.getAsJsonArray("frames") : null;
                    if (frameArray != null && frameArray.size() > 0) {
                        frameOrder = new int[frameArray.size()];
                        int max = 0;
                        for (int i = 0; i < frameArray.size(); i++) {
                            JsonElement e = frameArray.get(i);
                            int value = e.isJsonObject() ? intOr(e.getAsJsonObject(), "index", 0) : e.getAsInt();
                            frameOrder[i] = value;
                            max = Math.max(max, value);
                        }
                        frames = Math.max(frames, max + 1);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[tooltipfix] could not parse the metadata of {} ({})", spriteId, texture, e);
            }
        }

        return new GuiSprite(texture, packId, frameWidth, frameHeight, frames, frameOrder, frameTimeMs,
                left, top, right, bottom, nineSlice, stretchInner);
    }

    /** 只读 PNG 头部拿尺寸（IHDR），避免为了帧数解析整张图。 */
    private static int[] readPngSize(ResourceManager resources, ResourceLocation texture) {
        try (InputStream in = resources.getResourceStack(texture).get(0).open()) {
            byte[] header = in.readNBytes(24);
            if (header.length >= 24 && (header[0] & 0xFF) == 0x89 && header[1] == 'P') {
                int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                        | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
                int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                        | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
                if (width > 0 && height > 0) {
                    return new int[]{width, height};
                }
            }
        } catch (Exception ignored) {
            // 读不出来就当 1x1，后面按整图处理
        }
        return new int[]{1, 1};
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    public String packId() {
        return this.packId;
    }

    public int frameWidth() {
        return this.frameWidth;
    }

    public int frameHeight() {
        return this.frameHeight;
    }

    public boolean isNineSlice() {
        return this.nineSlice;
    }

    /** 诊断用：把解析出来的几何信息压成一行（全部按 mcmeta 的逻辑单位）。 */
    public String describe() {
        return "nineSlice=" + this.nineSlice + " stretchInner=" + this.stretchInner
                + " border=" + this.borderLeft + "/" + this.borderTop + "/" + this.borderRight + "/" + this.borderBottom
                + " canvas=" + this.frameWidth + "x" + this.frameHeight
                + " frames=" + this.frames + " frameMs=" + this.frameTimeMs;
    }

    private int currentFrame() {
        if (this.frames <= 1 || this.frameTimeMs <= 0) {
            return 0;
        }
        long tick = Util.getMillis() / (long) this.frameTimeMs;
        // mcmeta 只写了 animation（没写 frames 数组）时按顺序循环全部帧。CozyUI 的
        // container/inventory/effect_background_large 就是这种：以前这里要求 frameOrder.length > 1，
        // 于是永远停在第 0 帧，动态贴图变成静态图。
        if (this.frameOrder.length <= 1) {
            return (int) Math.floorMod(tick, (long) this.frames);
        }
        int index = (int) (tick % (long) this.frameOrder.length);
        if (index < 0) {
            index += this.frameOrder.length;
        }
        int frame = this.frameOrder[index];
        return frame >= 0 && frame < this.frames ? frame : 0;
    }

    private float vBase() {
        return (float) (this.currentFrame() * this.frameHeight);
    }

    private int texWidth() {
        return this.frameWidth;
    }

    private int texHeight() {
        return this.frameHeight * Math.max(1, this.frames);
    }

    /**
     * 目标尺寸下九宫格内区被拉伸的倍数（非九宫格、或内区不平铺拉伸时为 1）。
     *
     * <p>调用方用它做守卫：材质包的画布比目标小很多时内区会被拉几十倍，画面必然是糊的色条
     * （例：CozyUI 的 widget/button_highlighted 画布 80x20、border 34/34，放到 200x20 的按钮上
     * 内区只有 12 逻辑像素却要铺到 132，等于 11 倍），这种包一般自带 1.20.1 的 widgets.png 旧图，
     * 回落原版反而更接近作者在 1.20.1 上的原意。
     */
    public float innerStretch(int width, int height) {
        if (!this.nineSlice || !this.stretchInner) {
            return 1.0F;
        }
        int safeWidth = Math.max(0, width);
        int safeHeight = Math.max(0, height);
        int left = Math.min(this.borderLeft, safeWidth / 2);
        int right = Math.min(this.borderRight, safeWidth / 2);
        int top = Math.min(this.borderTop, safeHeight / 2);
        int bottom = Math.min(this.borderBottom, safeHeight / 2);
        return Math.max(stretchFactor(this.frameWidth - left - right, safeWidth - left - right),
                stretchFactor(this.frameHeight - top - bottom, safeHeight - top - bottom));
    }

    private static float stretchFactor(int band, int inner) {
        if (band <= 0 || inner <= band) {
            return 1.0F;
        }
        return (float) inner / (float) band;
    }

    /** 整张 sprite 画进 (x, y, width, height)：nine_slice 走九宫格，否则整图拉伸。 */
    public void draw(GuiGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (LINEAR_TEXTURES.contains(this.texture)) {
            applyLinearFilter(this.texture);
        }
        float v = vBase();
        if (!this.nineSlice) {
            graphics.blit(this.texture, x, y, width, height, 0.0F, v,
                    this.frameWidth, this.frameHeight, texWidth(), texHeight());
            return;
        }

        int left = Math.min(this.borderLeft, width / 2);
        int right = Math.min(this.borderRight, width / 2);
        int top = Math.min(this.borderTop, height / 2);
        int bottom = Math.min(this.borderBottom, height / 2);
        int bandWidth = this.frameWidth - left - right;
        int bandHeight = this.frameHeight - top - bottom;
        int innerWidth = width - left - right;
        int innerHeight = height - top - bottom;
        int rightX = x + width - right;
        int bottomY = y + height - bottom;
        int tw = texWidth();
        int th = texHeight();

        blit(graphics, x, y, left, top, 0, v, left, top, tw, th);
        blit(graphics, rightX, y, right, top, this.frameWidth - right, v, right, top, tw, th);
        blit(graphics, x, bottomY, left, bottom, 0, v + this.frameHeight - bottom, left, bottom, tw, th);
        blit(graphics, rightX, bottomY, right, bottom,
                this.frameWidth - right, v + this.frameHeight - bottom, right, bottom, tw, th);

        if (this.stretchInner) {
            blit(graphics, x + left, y, innerWidth, top, left, v, bandWidth, top, tw, th);
            blit(graphics, x + left, bottomY, innerWidth, bottom,
                    left, v + this.frameHeight - bottom, bandWidth, bottom, tw, th);
            blit(graphics, x, y + top, left, innerHeight, 0, v + top, left, bandHeight, tw, th);
            blit(graphics, rightX, y + top, right, innerHeight,
                    this.frameWidth - right, v + top, right, bandHeight, tw, th);
            blit(graphics, x + left, y + top, innerWidth, innerHeight,
                    left, v + top, bandWidth, bandHeight, tw, th);
        } else {
            tile(graphics, x + left, y, innerWidth, top, left, v, bandWidth, top, tw, th, true);
            tile(graphics, x + left, bottomY, innerWidth, bottom,
                    left, v + this.frameHeight - bottom, bandWidth, bottom, tw, th, true);
            tile(graphics, x, y + top, left, innerHeight, 0, v + top, left, bandHeight, tw, th, false);
            tile(graphics, rightX, y + top, right, innerHeight,
                    this.frameWidth - right, v + top, right, bandHeight, tw, th, false);
            tile(graphics, x + left, y + top, innerWidth, innerHeight,
                    left, v + top, bandWidth, bandHeight, tw, th, true);
        }
    }

    /**
     * 1:1 裁剪式绘制：从当前动画帧的逻辑坐标 (srcX, srcY) 开始取 (width, height) 一块贴到 (x, y)。
     * 进度条就是这么画的（1.20.2 的经验条/跳跃条用带源矩形的 blitSprite 重载）。
     */
    public void drawRegion(GuiGraphics graphics, int x, int y, int width, int height,
                           int srcX, int srcY, int srcLogicalWidth, int srcLogicalHeight) {
        if (width <= 0 || height <= 0 || srcLogicalWidth <= 0 || srcLogicalHeight <= 0) {
            return;
        }
        // 调用方给的是 GUI 逻辑空间（例：经验条 182x5），sprite 自己的绘制空间可能是纹理像素
        // （例：728x20）。先把源矩形换算过去，再按绘制空间归一化，否则只采到帧里的一小条。
        float sx = (float) this.frameWidth / (float) srcLogicalWidth;
        float sy = (float) this.frameHeight / (float) srcLogicalHeight;
        float v = vBase() + (float) srcY * sy;
        graphics.blit(this.texture, x, y, width, height,
                (float) srcX * sx, v,
                Math.max(1, Math.round(width * sx)), Math.max(1, Math.round(height * sy)),
                texWidth(), texHeight());
    }

    /** 内区平铺（1.20.1 原版语义）：声明了 stretch_inner 的包不会走这里。 */
    private void tile(GuiGraphics graphics, int x, int y, int destW, int destH,
                      int srcX, float srcY, int tileW, int tileH,
                      int texWidth, int texHeight, boolean horizontal) {
        int span = horizontal ? destW : destH;
        int tile = horizontal ? tileW : tileH;
        if (span <= 0 || tile <= 0) {
            return;
        }
        for (int offset = 0; offset < span; offset += tile) {
            int size = Math.min(tile, span - offset);
            if (horizontal) {
                blit(graphics, x + offset, y, size, destH, srcX, srcY, size, tileH, texWidth, texHeight);
            } else {
                blit(graphics, x, y + offset, destW, size, srcX, srcY, tileW, size, texWidth, texHeight);
            }
        }
    }

    /** 源尺寸与目标尺寸是两个独立参数，别把目标尺寸当 UV 范围用（会越界采到邻居）。 */
    private void blit(GuiGraphics graphics, int x, int y, int destW, int destH,
                      int u, float v, int srcW, int srcH, int texWidth, int texHeight) {
        if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) {
            return;
        }
        graphics.blit(this.texture, x, y, destW, destH, (float) u, v, srcW, srcH, texWidth, texHeight);
    }
}
