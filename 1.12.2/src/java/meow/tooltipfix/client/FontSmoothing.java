package meow.tooltipfix.client;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * 让 1.12.2 的字体贴图改用线性过滤，把「像素字」变成「平滑字」。
 *
 * <p><b>为什么需要这一步：</b>1.12.2 的 {@code FontRenderer} 把字体贴图硬编码成
 * {@code ascii.png} 128x128（8x8 格）和 {@code unicode_page_XX.png} 256x256（16x16 格，每格 16 texel
 * 画进 8 像素的方框）。材质包 CozyUI 的字体是 36x36 或 48x48 texel 的高清字形、行高 12 像素，缩到
 * 8 像素格后笔画只剩 1 像素；原版又用 {@code GL_NEAREST} 采样，于是 16→8 的降采样直接丢笔画、
 * 8→N 的放大又是一块块硬边，看起来就是「不是 CozyUI 的平滑字体」。
 *
 * <p>把字体贴图设成 {@code GL_LINEAR}（不生成 mipmap，避免相邻字形串色）之后：16 texel 缩到 8 像素
 * 变成真正的双线性降采样（笔画保留为灰度过渡），8 像素再放大到屏幕分辨率时是平滑插值。
 *
 * <p>资源包重载会重新上传贴图、把过滤参数改回 {@code GL_NEAREST}，而 unicode 页是按需加载的，
 * 所以这里每 20 tick 扫一遍贴图表，持续约 40 秒（足够覆盖开局会用到的所有页），重载时重新计时。
 *
 * <p><b>默认关闭（2026-10-18 改）：</b>这条规则的前提是「字体贴图确实是高清字形」，可它对**任何**
 * 字体贴图都生效。用户没装现代字体包时，原版 {@code ascii.png} 只有 128x128（每格 8 texel），
 * guiScale=4 时等于 4 倍双线性放大 ⇒ 灰字糊成一片、笔画之间冒出斑点（白字因为对比高看着还行），
 * 窗口模式 guiScale 小才显得正常；装了老式 2 倍字体包（如 {@code Slightly-Improved-Font}，256x256）
 * 也一样糊。1.12.2 原版就是 {@code GL_NEAREST}，而本项目的位置是「让材质包在 1.12.2 上正确显示」，
 * 不是「把 CozyUI 的观感强加给所有包」——所以改成 opt-in，需要时加 {@code -Dtooltipfix.fontsmooth=true}。
 * 真正的高清字体走 {@link HdFont}（读现代包的 {@code font/default.json}），不靠这个开关。
 */
public final class FontSmoothing {

    private static final boolean ENABLED = Boolean.getBoolean("tooltipfix.fontsmooth");

    /** 每 20 tick 扫一次，最多扫 40 次。 */
    private static final int MAX_SWEEPS = 40;

    private static int ticks;
    private static int sweeps;
    private static int smoothedTextures;
    private static Field textureMapField;

    private FontSmoothing() {
    }

    /** 资源包重载后调用：贴图会被重新上传，过滤参数回到 NEAREST，需要重新扫。 */
    public static void reset() {
        ticks = 0;
        sweeps = 0;
        smoothedTextures = 0;
    }

    public static void tick() {
        // HD 字体引擎接管后，老式 8px 图集只剩兜底用途；再给它加线性过滤只会让
        // 任何走老路径的文字（加载界面、未收录字形）糊成一片，所以直接让位。
        if (HdFont.available()) {
            return;
        }
        if (!ENABLED || ++ticks % 20 != 0 || sweeps >= MAX_SWEEPS) {
            return;
        }
        sweeps++;
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft == null) {
            return;
        }
        TextureManager manager = minecraft.func_110434_K();
        Map<ResourceLocation, ITextureObject> textures = textureMap(manager);
        if (textures == null) {
            return;
        }
        int bound = GuiRender.boundTexture();
        try {
            for (Map.Entry<ResourceLocation, ITextureObject> entry : textures.entrySet()) {
                ResourceLocation location = entry.getKey();
                if (location == null || location.func_110623_a() == null
                        || !location.func_110623_a().startsWith("textures/font/")) {
                    continue;
                }
                ITextureObject texture = entry.getValue();
                if (texture == null) {
                    continue;
                }
                int id = texture.func_110552_b();
                if (id <= 0) {
                    continue;
                }
                GlStateManager.func_179144_i(id);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                smoothedTextures++;
            }
        } finally {
            GuiRender.rebind(bound);
        }
        if (Debug.ON) {
            Debug.info("font smoothing: " + textures.size() + " textures in map, sweep " + sweeps + "/" + MAX_SWEEPS
                    + ", font textures touched " + smoothedTextures);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, ITextureObject> textureMap(TextureManager manager) {
        if (manager == null) {
            return null;
        }
        try {
            if (textureMapField == null) {
                Field fallback = null;
                for (Field field : TextureManager.class.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    if (fallback == null) {
                        fallback = field;
                    }
                    Object value = field.get(manager);
                    if (value instanceof Map && !((Map<?, ?>) value).isEmpty() && isTextureMap((Map<?, ?>) value)) {
                        textureMapField = field;
                        return (Map<ResourceLocation, ITextureObject>) value;
                    }
                }
                textureMapField = fallback;
                return fallback == null ? null : (Map<ResourceLocation, ITextureObject>) fallback.get(manager);
            }
            return (Map<ResourceLocation, ITextureObject>) textureMapField.get(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Debug.once("font-smoothing-reflect", "font smoothing: cannot read TextureManager map: " + e);
            return null;
        }
    }

    private static boolean isTextureMap(Map<?, ?> map) {
        for (Object value : map.values()) {
            return value instanceof ITextureObject;
        }
        return true;
    }
}
