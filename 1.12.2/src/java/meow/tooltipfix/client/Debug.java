package meow.tooltipfix.client;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 排查用的打点。只有 {@code -Dtooltipfix.debug=true} 时才会产生日志，正式游戏里每个方法都是
 * 「读一个 static final boolean 然后 return」，不额外分配字符串。
 *
 * <p>为什么需要它：1.12.2 的 blit 调用点全是 {@code int u,v,w,h} 这种裸参数，光看代码永远不知道
 * 眼前这坨像素是「哪个调用点 + 哪张贴图 + 哪个 UV」画出来的。这里把每个不同的
 * (贴图, UV, 目标尺寸) 组合只打一次，一次实机跑就能拿到完整的「谁画了什么」清单。
 */
public final class Debug {

    public static final boolean ON = Boolean.getBoolean("tooltipfix.debug");

    /**
     * 全量旁路：为 true 时装的所有 sprite 替换一律不接管，退回原版 quad 绘制。
     *
     * <p>这是 A/B 对照用的开关（selftest 会临时打开再截图），因为只有「同一窗口、同一 GUI scale
     * 下开着材质包的纯原版画法」才能当基准——用别的截图对比会把缩放差异当成错位。
     */
    public static boolean passthrough = Boolean.getBoolean("tooltipfix.passthrough");

    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");
    private static final Set<String> SEEN = new HashSet<>();
    private static Map<ResourceLocation, ITextureObject> textureObjects;
    private static boolean textureObjectsResolved;
    /** GL 贴图 id → 资源名（含 null 值：查过但没有对应资源，避免反复全表扫）。 */
    private static final Map<Integer, ResourceLocation> GL_NAMES = new HashMap<>();

    private Debug() {
    }

    public static void info(String message) {
        if (ON) {
            LOGGER.info("[tooltipfix] " + message);
        }
    }

    /** 同一 key 只打一次。key 里要带足够的区分度，否则会把不同调用点合成一条。 */
    public static void once(String key, String message) {
        if (!ON) {
            return;
        }
        synchronized (SEEN) {
            if (!SEEN.add(key)) {
                return;
            }
        }
        LOGGER.info("[tooltipfix] " + message);
    }

    /** {@code Gui.drawTexturedModalRect} 的入口打点：打「这坨像素是从哪来的」。 */
    public static void blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (!ON) {
            return;
        }
        String texture = textureName(GuiRender.boundTexture());
        once("blit|" + texture + "|" + u + "," + v + "," + width + "," + height + "@" + x + "," + y,
                "blit tex=" + texture + " src=(" + u + "," + v + "," + width + "x" + height
                        + ") dst=(" + x + "," + y + ") z=" + zLevel);
    }

    /** {@code Gui.drawGradientRect} 的入口打点。颜色是 tooltip/槽位高亮的唯一身份标识。 */
    public static void gradient(float zLevel, int left, int top, int right, int bottom,
                                int colorFrom, int colorTo) {
        if (!ON) {
            return;
        }
        once("gradient|" + colorFrom + "|" + colorTo + "|" + (right - left) + "x" + (bottom - top),
                "gradient from=0x" + Integer.toHexString(colorFrom) + " to=0x" + Integer.toHexString(colorTo)
                        + " rect=(" + left + "," + top + ")-(" + right + "," + bottom + ") z=" + zLevel);
    }

    /** {@code Gui.drawRect} 的入口打点（槽位高亮/描边会走这里）。 */
    public static void rect(int left, int top, int right, int bottom, int color) {
        if (!ON) {
            return;
        }
        once("rect|" + color + "|" + (right - left) + "x" + (bottom - top),
                "rect color=0x" + Integer.toHexString(color) + " rect=(" + left + "," + top
                        + ")-(" + right + "," + bottom + ")");
    }

    /** 我们自己画 sprite 时打点：哪个 sprite、九宫格与否、贴到哪、原始纹理多大。 */
    public static void sprite(String spriteId, boolean nineSlice, float x, float y, int width, int height,
                              int texWidth, int texHeight) {
        once("sprite|" + spriteId + "|" + width + "x" + height,
                "sprite " + spriteId + " nine=" + nineSlice + " dst=(" + (int) x + "," + (int) y + ","
                        + width + "x" + height + ") tex=" + texWidth + "x" + texHeight);
    }

    /**
     * GL 贴图 id → {@code ResourceLocation}。原版自带的贴图在 {@code TextureManager} 的 map 里能查到；
     * 查不到的就是我们自己上传的（或别的 mod 的），返回 {@code ?gl=…} 让人工判断。
     */
    public static String textureName(int glTexture) {
        ResourceLocation location = textureLocation(glTexture);
        if (location != null) {
            return String.valueOf(location);
        }
        if (glTexture <= 0) {
            return "none";
        }
        return "?gl=" + glTexture;
    }

    /**
     * GL 贴图 id → {@code ResourceLocation}，查不到返回 null。
     *
     * <p>{@link LegacySheets} 每次 blit 都要问一次「现在绑的是哪张图」，所以结果必须缓存：
     * 一个 HUD 帧里有几百次 blit，线性扫 TextureManager 的 map 会直接拖垮帧率。
     */
    public static ResourceLocation textureLocation(int glTexture) {
        if (glTexture <= 0) {
            return null;
        }
        if (GL_NAMES.containsKey(glTexture)) {
            return GL_NAMES.get(glTexture);
        }
        Map<ResourceLocation, ITextureObject> map = textureObjects();
        ResourceLocation found = null;
        if (map != null) {
            try {
                for (Map.Entry<ResourceLocation, ITextureObject> entry : map.entrySet()) {
                    ITextureObject value = entry.getValue();
                    if (value != null && value.func_110552_b() == glTexture) {
                        found = entry.getKey();
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // 并发修改/字段变化都不该影响排查本身
            }
        }
        GL_NAMES.put(glTexture, found);
        return found;
    }

    /** 资源包重载后 GL 贴图 id 会被回收复用，缓存必须清掉。 */
    public static void resetTextureCache() {
        GL_NAMES.clear();
        textureObjects = null;
        textureObjectsResolved = false;
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, ITextureObject> textureObjects() {
        if (textureObjectsResolved) {
            return textureObjects;
        }
        textureObjectsResolved = true;
        try {
            TextureManager manager = Minecraft.func_71410_x().func_110434_K();
            for (Field field : TextureManager.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(manager);
                if (value instanceof Map) {
                    textureObjects = (Map<ResourceLocation, ITextureObject>) value;
                    break;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[tooltipfix] cannot resolve texture names via reflection", t);
            textureObjects = null;
        }
        return textureObjects;
    }
}
