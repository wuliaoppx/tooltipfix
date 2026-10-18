package meow.tooltipfix.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

/**
 * 1.12.2 用的「GUI sprite」实现：把材质包里的 {@code assets/minecraft/textures/gui/sprites/**.png}
 * 当作 1.20.2+ 那样的 sprite 来画。
 *
 * <p>1.12.2 完全没有 GUI sprite 管线（那是 1.20.2 才引入的），所以这里自己：
 * <ul>
 *   <li>读 {@code .png.mcmeta}：{@code gui.scaling}（stretch / nine_slice + border + stretch_inner）与
 *       {@code animation}（frametime / frames / 每帧逻辑尺寸）；解析用 lenient reader，跟 vanilla 一致，
 *       这样带 {@code //} 注释的 mcmeta（CozyUI 就有）也能读；</li>
 *   <li>自己按时间步进动画帧（原版 {@code SimpleTexture} 不会给独立贴图播动画）；</li>
 *   <li>用 {@link GuiRender} 的「源矩形 / 目标矩形分开」贴图来画九宫格、拉伸或平铺。</li>
 * </ul>
 *
 * <p>逻辑尺寸（mcmeta 里写的 width/height）和实际 png 像素尺寸不需要一致：UV 全部按绘制空间算，
 * 归一化时也除以逻辑尺寸，比例相同即可，GPU 会自己采到正确位置。
 *
 * <p>移植自 1.20.1 变体的同名类，绘制原语、资源读取、时间来源换成 1.12.2 的 API。
 */
public final class GuiSprite {
    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");

    private static final Map<ResourceLocation, GuiSprite> CACHE = new HashMap<>();
    /** 只从游戏自带资源里读的图（{@link #ofVanilla(String)}），键是贴图路径。 */
    private static final Map<String, GuiSprite> VANILLA_CACHE = new HashMap<>();
    /** 按贴图路径读的整图（{@link #ofTexture(ResourceLocation)}）：1.12.2 的老式图集走这条。 */
    private static final Map<ResourceLocation, GuiSprite> TEXTURE_CACHE = new HashMap<>();
    /** 我们自己上传的 GL 贴图：sprite id -> gl id（资源重载时要全部删掉重建）。 */
    private static final Map<ResourceLocation, Integer> OWN_TEXTURES = new HashMap<>();
    private static final java.util.Set<String> LOGGED_LOAD = new java.util.HashSet<>();

    /** 需要 GL_LINEAR 过滤的 sprite（只点这两张，理由见 {@link #applyLinearFilter()}）。 */
    private static final java.util.Set<ResourceLocation> LINEAR_TEXTURES = new java.util.HashSet<>();

    static {
        LINEAR_TEXTURES.add(new ResourceLocation("minecraft", "container/slot_highlight_back"));
        LINEAR_TEXTURES.add(new ResourceLocation("minecraft", "container/slot_highlight_front"));
    }

    /** GUI sprite 的贴图 id 前缀，和 1.21.2 里 {@code SpriteSourceList} 注册的路径一致。 */
    public static final String SPRITE_DIRECTORY = "textures/gui/sprites/";

    private final ResourceLocation spriteId;
    private final String packId;
    private final int glTexture;
    private final int width;
    private final int height;
    private final int frames;
    private final int[] frameOrder;
    private final int frameTimeMs;
    private final int borderLeft;
    private final int borderTop;
    private final int borderRight;
    private final int borderBottom;
    private final boolean nineSlice;
    private final boolean stretchInner;

    private GuiSprite(ResourceLocation spriteId, String packId, int glTexture, int width, int height, int frames,
                      int[] frameOrder, int frameTimeMs, int borderLeft, int borderTop, int borderRight,
                      int borderBottom, boolean nineSlice, boolean stretchInner) {
        this.spriteId = spriteId;
        this.packId = packId;
        this.glTexture = glTexture;
        this.width = width;
        this.height = height;
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
        GuiSprite cached = CACHE.get(spriteId);
        if (cached != null) {
            return cached;
        }
        Minecraft minecraft = Minecraft.func_71410_x();
        IResourceManager resources = minecraft == null ? null : minecraft.func_110442_L();
        if (resources == null) {
            return null;
        }
        try {
            GuiSprite loaded = load(spriteId, resources);
            if (loaded == null) {
                return null;
            }
            CACHE.put(spriteId, loaded);
            if (LOGGED_LOAD.add(spriteId.toString())) {
                LOGGER.info("[tooltipfix] sprite {} packed from '{}' {}", spriteId, loaded.packId(),
                        loaded.describe());
            }
            return loaded;
        } catch (Throwable t) {
            LOGGER.warn("[tooltipfix] failed to load sprite {}", spriteId, t);
            return null;
        }
    }

    /**
     * 只从**游戏自带资源**（jar 里的 DefaultResourcePack）读一张整图，完全忽略材质包。
     *
     * <p>用途：材质包按 1.21 的布局改造过、把 1.12.2 还要从固定贴图里抠的东西删掉了。最典型的是药水
     * 效果图标 —— 1.12.2 从 {@code textures/gui/container/inventory.png} 的 (0,198) 起 8x3 个 18x18
     * 网格里取，而 CozyUI 把这块换成了圆角方框（1.21 改用单独贴图），于是图标位置只剩空白。
     * 这种「材质包没有、但原版 jar 有」的资源必须回落到 jar，不能回落到材质包。
     *
     * @param path 相对 {@code assets/minecraft/} 的贴图路径，例如 {@code textures/gui/container/inventory.png}
     * @return 读不到时返回 null
     */
    public static GuiSprite ofVanilla(String path) {
        GuiSprite cached = VANILLA_CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft == null) {
            return null;
        }
        ResourceLocation location = new ResourceLocation("minecraft", path);
        try (InputStream in = vanillaStream(minecraft, location)) {
            if (in == null) {
                return null;
            }
            BufferedImage image = TextureUtil.func_177053_a(in);
            if (image == null) {
                return null;
            }
            int glTexture = upload(image);
            OWN_TEXTURES.put(location, glTexture);
            GuiSprite loaded = new GuiSprite(location, "Default", glTexture,
                    image.getWidth(), image.getHeight(), 1, new int[]{0}, -1,
                    0, 0, 0, 0, false, false);
            VANILLA_CACHE.put(path, loaded);
            if (LOGGED_LOAD.add("vanilla:" + path)) {
                LOGGER.info("[tooltipfix] vanilla sheet {} loaded {}x{}", path,
                        image.getWidth(), image.getHeight());
            }
            return loaded;
        } catch (Throwable t) {
            LOGGER.warn("[tooltipfix] failed to load vanilla sheet {}", path, t);
            return null;
        }
    }

    /**
     * 按**贴图路径**读整张图（不是 sprite id），从资源栈最上层（材质包优先）取。
     *
     * <p>用途：1.12.2 的老式图集（{@code textures/gui/icons.png}、{@code widgets.png}、
     * {@code container/*.png}）在材质包里常常是 4 倍尺寸，而原版调用方把 UV 写死成 1/256，
     * 只取到左上角 1/4。{@link LegacySheets} 需要拿到「包内这张图」的原始尺寸才能换算源矩形。
     *
     * @return 读不到时返回 null
     */
    public static GuiSprite ofTexture(ResourceLocation texture) {
        GuiSprite cached = TEXTURE_CACHE.get(texture);
        if (cached != null) {
            return cached;
        }
        Minecraft minecraft = Minecraft.func_71410_x();
        IResourceManager resources = minecraft == null ? null : minecraft.func_110442_L();
        if (resources == null) {
            return null;
        }
        try {
            List<IResource> stack = resourceStack(resources, texture);
            if (stack.isEmpty()) {
                return null;
            }
            IResource top = stack.get(stack.size() - 1);
            BufferedImage image;
            try (InputStream in = top.func_110527_b()) {
                image = TextureUtil.func_177053_a(in);
            }
            if (image == null) {
                return null;
            }
            int glTexture = upload(image);
            OWN_TEXTURES.put(texture, glTexture);
            GuiSprite loaded = new GuiSprite(texture, top.func_177240_d(), glTexture,
                    image.getWidth(), image.getHeight(), 1, new int[]{0}, -1,
                    0, 0, 0, 0, false, false);
            TEXTURE_CACHE.put(texture, loaded);
            if (LOGGED_LOAD.add("texture:" + texture)) {
                LOGGER.info("[tooltipfix] legacy sheet {} loaded {}x{} from '{}'", texture,
                        image.getWidth(), image.getHeight(), loaded.packId());
            }
            return loaded;
        } catch (Throwable t) {
            LOGGER.warn("[tooltipfix] failed to load legacy sheet {}", texture, t);
            return null;
        }
    }

    /**
     * 从资源栈里挑出**游戏自带**（DefaultResourcePack）的那一份。资源栈按优先级从低到高排，
     * 最后一个是材质包、第一个/名字为 Default 的才是 jar 本身。
     */
    private static InputStream vanillaStream(Minecraft minecraft, ResourceLocation location) throws Exception {
        List<IResource> stack = minecraft.func_110442_L().func_135056_b(location);
        if (stack.isEmpty()) {
            return null;
        }
        IResource pick = null;
        for (IResource resource : stack) {
            if ("Default".equals(resource.func_177240_d())) {
                pick = resource;
                break;
            }
        }
        if (pick == null) {
            pick = stack.get(0);
        }
        return pick.func_110527_b();
    }

    public static void invalidateCache() {
        VANILLA_CACHE.clear();
        TEXTURE_CACHE.clear();
        for (Integer id : OWN_TEXTURES.values()) {
            if (id != null && id > 0) {
                try {
                    TextureUtil.func_147942_a(id);
                } catch (Throwable ignored) {
                }
            }
        }
        OWN_TEXTURES.clear();
        CACHE.clear();
        LOGGED_LOAD.clear();
    }

    private static GuiSprite load(ResourceLocation spriteId, IResourceManager resources) throws Exception {
        ResourceLocation texture = textureOf(spriteId);
        ResourceLocation meta = new ResourceLocation(texture.func_110624_b(), texture.func_110623_a() + ".mcmeta");
        List<IResource> stack = resourceStack(resources, texture);
        if (stack.isEmpty()) {
            return null;
        }
        // 1.12.2 的 getResourceStack 按「优先级从低到高」排，最后一个才是资源包列表里最上面的那个
        // （CozyUI 就在最上面），所以取末尾。
        IResource png = stack.get(stack.size() - 1);

        int width = 0;
        int height = 0;
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
        int animBaseWidth = 0;
        int animBaseHeight = 0;

        List<IResource> metaStack = resourceStack(resources, meta);
        if (!metaStack.isEmpty()) {
            try (InputStream in = metaStack.get(metaStack.size() - 1).func_110527_b();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonReader jsonReader = new JsonReader(reader);
                jsonReader.setLenient(true);
                // gson 2.8.0（1.12.2 自带）没有 JsonParser.parseReader，用老的实例方法。
                JsonObject root = new JsonParser().parse(jsonReader).getAsJsonObject();

                JsonObject gui = root.has("gui") ? root.getAsJsonObject("gui") : null;
                JsonObject scaling = gui != null && gui.has("scaling") ? gui.getAsJsonObject("scaling") : null;
                if (scaling != null && "nine_slice".equals(stringOr(scaling, "type", "stretch"))) {
                    scalingSized = scaling.has("width") && scaling.has("height");
                    nineSlice = true;
                    stretchInner = scaling.has("stretch_inner") && scaling.get("stretch_inner").getAsBoolean();
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
                        width = intOr(scaling, "width", 0);
                    }
                    if (scaling.has("height")) {
                        height = intOr(scaling, "height", 0);
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
                        width = animWidth;
                        height = animHeight;
                    }
                    int baseWidth = animWidth > 0 ? animWidth : width;
                    int baseHeight = animHeight > 0 ? animHeight : height;
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
                        frames = max + 1;
                    }
                    animBaseWidth = baseWidth;
                    animBaseHeight = baseHeight;
                }
            } catch (Exception e) {
                LOGGER.warn("[tooltipfix] could not parse the metadata of {} ({})", spriteId, meta, e);
            }
        }

        BufferedImage image = TextureUtil.func_177053_a(png.func_110527_b());
        if (image == null) {
            return null;
        }
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (width <= 0) {
            width = imageWidth;
        }
        if (height <= 0) {
            height = imageHeight;
        }
        if (frameTimeMs > 0) {
            int baseWidth = animBaseWidth > 0 ? animBaseWidth : width;
            int baseHeight = animBaseHeight > 0 ? animBaseHeight : height;
            int scale = baseWidth > 0 && imageWidth % baseWidth == 0 ? imageWidth / baseWidth : 1;
            int realFrameHeight = baseHeight * scale;
            if (realFrameHeight > 0) {
                frames = Math.max(1, imageHeight / realFrameHeight);
            }
            if (frameOrder.length > 1) {
                int max = 0;
                for (int value : frameOrder) {
                    max = Math.max(max, value);
                }
                frames = Math.max(frames, max + 1);
            }
        }

        int glTexture = upload(image);
        OWN_TEXTURES.put(spriteId, glTexture);
        // 1.12.2 的 IResource.func_177240_d() 就是「这个资源来自哪个材质包」（1.20 改名叫 getResourcePackName）。
        return new GuiSprite(spriteId, png.func_177240_d(), glTexture, width, height, frames, frameOrder,
                frameTimeMs, left, top, right, bottom, nineSlice, stretchInner);
    }

    private static int upload(BufferedImage image) {
        int id = GL11.glGenTextures();
        TextureUtil.func_110989_a(id, image, false, false);
        return id;
    }

    private static List<IResource> resourceStack(IResourceManager resources, ResourceLocation location) {
        try {
            return resources.func_135056_b(location);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** sprite id（如 {@code minecraft:hud/hotbar}）→ 贴图路径。 */
    public static ResourceLocation textureOf(ResourceLocation spriteId) {
        return new ResourceLocation(spriteId.func_110624_b(), SPRITE_DIRECTORY + spriteId.func_110623_a() + ".png");
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
        return this.width;
    }

    public int frameHeight() {
        return this.height;
    }

    public boolean isNineSlice() {
        return this.nineSlice;
    }

    /**
     * 九宫格左边框的宽度（mcmeta 的逻辑单位）。
     *
     * <p>用来决定「该往外扩多少」：1.21.2 的 sprite 画布比实际要画的框大一圈（tooltip 是 60x60
     * 画布、border=16），按原版矩形直接贴会把圆角和阴影压扁，必须按边框宽度外扩。
     */
    public int borderLeft() {
        return this.borderLeft;
    }

    public int borderTop() {
        return this.borderTop;
    }

    /** 诊断用：把解析出来的几何信息压成一行（全部按 mcmeta 的逻辑单位）。 */
    public String describe() {
        return "nineSlice=" + this.nineSlice + " stretchInner=" + this.stretchInner
                + " border=" + this.borderLeft + "/" + this.borderTop + "/" + this.borderRight + "/" + this.borderBottom
                + " canvas=" + this.width + "x" + this.height
                + " frames=" + this.frames + " frameMs=" + this.frameTimeMs;
    }

    private int currentFrame() {
        if (this.frames <= 1 || this.frameTimeMs <= 0) {
            return 0;
        }
        long tick = Minecraft.func_71386_F() / (long) this.frameTimeMs;
        // mcmeta 只写了 animation（没写 frames 数组）时按顺序循环全部帧。CozyUI 的
        // container/inventory/effect_background_large 就是这种：要求 frameOrder.length > 1 时
        // 会永远停在第 0 帧，动态贴图变成静态图。
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
        return (float) (this.currentFrame() * this.height);
    }

    private int texWidth() {
        return this.width;
    }

    private int texHeight() {
        return this.height * Math.max(1, this.frames);
    }

    /**
     * 目标尺寸下九宫格内区被拉伸的倍数（非九宫格、或内区不平铺拉伸时为 1）。
     *
     * <p>调用方用它做守卫：材质包的画布比目标小很多时内区会被拉几十倍，画面必然是糊的色条
     * （例：CozyUI 的 widget/button_highlighted 画布 80x20、border 34/34，放到 200x20 的按钮上
     * 内区只有 12 逻辑像素却要铺到 132，等于 11 倍）。
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
        return Math.max(stretchFactor(this.width - left - right, safeWidth - left - right),
                stretchFactor(this.height - top - bottom, safeHeight - top - bottom));
    }

    private static float stretchFactor(int band, int inner) {
        if (band <= 0 || inner <= band) {
            return 1.0F;
        }
        return (float) inner / (float) band;
    }

    /**
     * 整张 sprite 画进 (x, y, width, height)：nine_slice 走九宫格，否则整图拉伸。
     *
     * <p>整次绘制包在 GL 状态快照里：我们是顶在 vanilla {@code drawTexturedModalRect} 的位置上的，
     * 那里原版一个 GL 状态都不改，所以改动必须当场还原，否则调用方后面几块会带着我们的状态画
     * （理由见 {@link GuiRender.GlSnapshot}）。
     */
    public void draw(float x, float y, int width, int height, float z) {
        if (width <= 0 || height <= 0) {
            return;
        }
        GuiRender.GlSnapshot snapshot = GuiRender.snapshot();
        try {
            drawInner(x, y, width, height, z);
        } finally {
            GuiRender.restore(snapshot);
        }
    }

    /**
     * 整图画进 (x, y, width, height)，但**不动混合函数**。
     *
     * <p>只有准星用：vanilla 画准星时把 blend 设成反色混合（{@code ONE_MINUS_DST_COLOR} /
     * {@code ONE_MINUS_SRC_COLOR}），材质包的准星图是白色十字、靠反色才变成深色；
     * 走普通 {@link #draw} 会把 blend 改成 alpha 混合，准星会突然变白。
     */
    public void drawKeepBlend(float x, float y, int width, int height, float z) {
        if (width <= 0 || height <= 0) {
            return;
        }
        GuiRender.GlSnapshot snapshot = GuiRender.snapshot();
        try {
            GuiRender.spriteKeepBlend(this.glTexture, x, y, width, height, 0.0F, vBase(),
                    this.width, this.height, texWidth(), texHeight(), z);
        } finally {
            GuiRender.restore(snapshot);
        }
    }

    private void drawInner(float x, float y, int width, int height, float z) {        Debug.sprite(this.spriteId.toString(), this.nineSlice, x, y, width, height, texWidth(), texHeight());
        if (LINEAR_TEXTURES.contains(this.spriteId)) {
            applyLinearFilter();
        }
        float v = vBase();
        if (!this.nineSlice) {
            GuiRender.sprite(this.glTexture, x, y, width, height, 0.0F, v, this.width, this.height,
                    texWidth(), texHeight(), z);
            return;
        }

        int left = Math.min(this.borderLeft, width / 2);
        int right = Math.min(this.borderRight, width / 2);
        int top = Math.min(this.borderTop, height / 2);
        int bottom = Math.min(this.borderBottom, height / 2);
        int bandWidth = this.width - left - right;
        int bandHeight = this.height - top - bottom;
        int innerWidth = width - left - right;
        int innerHeight = height - top - bottom;
        float rightX = x + width - right;
        float bottomY = y + height - bottom;
        int tw = texWidth();
        int th = texHeight();

        blit(x, y, left, top, 0, v, left, top, tw, th, z);
        blit(rightX, y, right, top, this.width - right, v, right, top, tw, th, z);
        blit(x, bottomY, left, bottom, 0, v + this.height - bottom, left, bottom, tw, th, z);
        blit(rightX, bottomY, right, bottom,
                this.width - right, v + this.height - bottom, right, bottom, tw, th, z);

        if (this.stretchInner) {
            blit(x + left, y, innerWidth, top, left, v, bandWidth, top, tw, th, z);
            blit(x + left, bottomY, innerWidth, bottom,
                    left, v + this.height - bottom, bandWidth, bottom, tw, th, z);
            blit(x, y + top, left, innerHeight, 0, v + top, left, bandHeight, tw, th, z);
            blit(rightX, y + top, right, innerHeight,
                    this.width - right, v + top, right, bandHeight, tw, th, z);
            blit(x + left, y + top, innerWidth, innerHeight,
                    left, v + top, bandWidth, bandHeight, tw, th, z);
        } else {
            tile(x + left, y, innerWidth, top, left, v, bandWidth, top, tw, th, z, true);
            tile(x + left, bottomY, innerWidth, bottom,
                    left, v + this.height - bottom, bandWidth, bottom, tw, th, z, true);
            tile(x, y + top, left, innerHeight, 0, v + top, left, bandHeight, tw, th, z, false);
            tile(rightX, y + top, right, innerHeight,
                    this.width - right, v + top, right, bandHeight, tw, th, z, false);
            tile(x + left, y + top, innerWidth, innerHeight,
                    left, v + top, bandWidth, bandHeight, tw, th, z, true);
        }
    }

    /**
     * 1:1 裁剪式绘制：从当前动画帧的逻辑坐标 (srcX, srcY) 开始取 (width, height) 一块贴到 (x, y)。
     * 进度条就是这么画的（1.20.2 的经验条/跳跃条用带源矩形的 blitSprite 重载）。
     */
    public void drawRegion(float x, float y, int width, int height,
                           int srcX, int srcY, int srcLogicalWidth, int srcLogicalHeight, float z) {
        if (width <= 0 || height <= 0 || srcLogicalWidth <= 0 || srcLogicalHeight <= 0) {
            return;
        }
        GuiRender.GlSnapshot snapshot = GuiRender.snapshot();
        try {
            drawRegionInner(x, y, width, height, srcX, srcY, srcLogicalWidth, srcLogicalHeight, z);
        } finally {
            GuiRender.restore(snapshot);
        }
    }

    private void drawRegionInner(float x, float y, int width, int height,
                                 int srcX, int srcY, int srcLogicalWidth, int srcLogicalHeight, float z) {
        // 调用方给的是 GUI 逻辑空间（例：经验条 182x5），sprite 自己的绘制空间可能是纹理像素
        // （例：728x20）。先把源矩形换算过去，再按绘制空间归一化，否则只采到帧里的一小条。
        Debug.sprite(this.spriteId.toString() + "#region", false, x, y, width, height, texWidth(), texHeight());
        float sx = (float) this.width / (float) srcLogicalWidth;
        float sy = (float) this.height / (float) srcLogicalHeight;
        float v = vBase() + (float) srcY * sy;
        GuiRender.sprite(this.glTexture, x, y, width, height,
                (float) srcX * sx, v,
                Math.max(1, Math.round(width * sx)), Math.max(1, Math.round(height * sy)),
                texWidth(), texHeight(), z);
    }

    /** 内区平铺（1.20.1 原版语义）：声明了 stretch_inner 的包不会走这里。 */
    private void tile(float x, float y, int destW, int destH,
                      int srcX, float srcY, int tileW, int tileH,
                      int texWidth, int texHeight, float z, boolean horizontal) {
        int span = horizontal ? destW : destH;
        int tile = horizontal ? tileW : tileH;
        if (span <= 0 || tile <= 0) {
            return;
        }
        for (int offset = 0; offset < span; offset += tile) {
            int size = Math.min(tile, span - offset);
            if (horizontal) {
                blit(x + offset, y, size, destH, srcX, srcY, size, tileH, texWidth, texHeight, z);
            } else {
                blit(x, y + offset, destW, size, srcX, srcY, tileW, size, texWidth, texHeight, z);
            }
        }
    }

    /** 源尺寸与目标尺寸是两个独立参数，别把目标尺寸当 UV 范围用（会越界采到邻居）。 */
    private void blit(float x, float y, int destW, int destH,
                      int u, float v, int srcW, int srcH, int texWidth, int texHeight, float z) {
        if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) {
            return;
        }
        GuiRender.sprite(this.glTexture, x, y, destW, destH, (float) u, v, srcW, srcH, texWidth, texHeight, z);
    }

    /**
     * 给 {@link #LINEAR_TEXTURES} 里点名的 sprite 设 GL_LINEAR。
     *
     * <p>为什么需要：这些 PNG 的分辨率是逻辑画布的整数倍（CozyUI 的 slot_highlight_* 是 24 逻辑画布配
     * 96x96 = 4 倍超采样）。GUI scale 不是 4 的整数倍时（用户整合包 guiScale=3 ⇒ 96 texel 要画到 72 个物理
     * 像素）GPU 得做 0.75 倍缩小，而 {@code TextureUtil.uploadTextureImage} 把 GL_TEXTURE_MIN/MAG_FILTER
     * 固定成 GL_NEAREST ⇒ 每 4 个 texel 丢掉 1 个，圆角自带的抗锯齿像素被抽样掉，圆角变成台阶、描边忽粗忽细
     * （用户原话「框的形状很奇怪，而且有锯齿」）。4 倍超采样下线性过滤等价于正确的箱式降采样；而 GUI scale 4
     * （1:1）时线性与最近邻的采样结果完全相同，所以不会引入副作用。
     *
     * <p>为什么不全局做：1.0.13 的「全局超采样降采样 + 线性过滤」把暂停菜单按钮、紫色选中按钮、高亮框一起搞
     * 发虚，被用户判为「效果更差了」（原话）。视觉修正必须限定目标 sprite。
     *
     * <p>每帧调用一次只花 3 次 GL 调用；好处是资源重载（上传时会把过滤重置回 NEAREST）或换材质包
     * 之后自动重新生效，不必另挂钩重载事件。
     */
    private void applyLinearFilter() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.glTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }
}
