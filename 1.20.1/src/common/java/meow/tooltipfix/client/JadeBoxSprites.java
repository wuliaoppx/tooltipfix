package meow.tooltipfix.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.theme.Theme;

/**
 * 让材质包能重绘 <b>Jade</b> 顶部信息框（overlay 提示框）——1.20.1 端的移植。
 *
 * <p>背景：Jade 11.x / 15.x 的信息框是用主题 JSON（{@code assets/jade/jade_themes/*.json}）里的
 * {@code tooltipStyle.backgroundColor / borderColor} 现画的，材质包只能改颜色，改不了贴图；
 * 而从 Jade 1.21.11 起，主题里改成写 {@code "tooltipStyle": {"sprite": "jade:waila", "tooltip": true}}，
 * 框体改由两张 GUI sprite 画出来：
 * <ul>
 *   <li>背景 = {@code jade:tooltip/<主题名>_background}（九宫格 + {@code stretch_inner}）；</li>
 *   <li>外框 = {@code jade:tooltip/<主题名>_frame}（没有 mcmeta，整图拉伸）。</li>
 * </ul>
 * 于是材质包只要放 {@code assets/jade/textures/gui/sprites/tooltip/waila_background.png} 就能换掉整个框
 * （CozyUI+ v1.11 就是这么做的：400×400、mcmeta 写九宫格逻辑画布 100×100、border 24、stretch_inner）。
 *
 * <p>1.20.1 没有 GUI sprite 图集，所以这里走本模组自带的 {@link GuiSprite}（直接读 PNG + mcmeta），
 * 命名规则与 1.21.11 完全一致，主题名取自 Jade 当前主题的 id 路径。
 *
 * <p>几何照抄 1.21.11 的 {@code BoxStyle#render}：tooltip 框的 sprite 每个方向比内容框外扩
 * {@value #OVERHANG} 像素，宽高各加 2×{@value #OVERHANG}。
 *
 * <p>材质包没提供对应 sprite 时返回 {@code false}，调用方继续用 Jade 原来的颜色画法
 * （红线：没装材质包时行为必须与原来一模一样）。
 */
public final class JadeBoxSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    /** 1.21.11 {@code BoxStyle.render} 里 tooltip 分支的外扩量。 */
    private static final int OVERHANG = 9;

    /** 默认主题 {@code jade:dark} 与材质包之间的命名空间（sprite 都挂在 jade 下）。 */
    private static final String JADE_NAMESPACE = "jade";

    /** 主题名 → sprite 名的后缀（1.21.11 的 {@code TooltipRenderUtil.getBackgroundSprite/FrameSprite}）。 */
    private static final String BACKGROUND_SUFFIX = "_background";
    private static final String FRAME_SUFFIX = "_frame";

    /** 解析结果缓存：主题不变就不必每帧去 ResourceManager 里找文件。 */
    private static String cachedKey;
    private static GuiSprite cachedBackground;
    private static GuiSprite cachedFrame;
    private static long cachedAt;
    private static String loggedKey;
    /** 诊断用：HEAD 注入到底有没有跑到（目标方法签名对不上时这里不会打印）。 */
    private static boolean hookLogged;

    private JadeBoxSprites() {
    }

    /** 由 mixin 在进入 Jade 的画框方法时调用：用来区分「注入没生效」与「材质包没提供 sprite」。 */
    public static void noteHookFired() {
        if (!hookLogged) {
            hookLogged = true;
            LOGGER.info("[tooltipfix] jade tooltip box hook reached");
        }
    }

    private static boolean themeLogged;

    /**
     * 尝试用材质包的 sprite 画 Jade 信息框。
     *
     * @return true = 已经画好了（调用方不要再画原来的颜色框）；false = 材质包没提供，照原样画。
     */
    public static boolean draw(GuiGraphics graphics, int x, int y, int width, int height, float alpha) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        Theme theme = currentTheme();
        if (theme == null || theme.id == null) {
            if (!themeLogged) {
                themeLogged = true;
                LOGGER.info("[tooltipfix] jade tooltip box: no active theme yet, keep the stock colour box");
            }
            return false;
        }
        String themePath = theme.id.getPath();
        String themeNamespace = theme.id.getNamespace();
        String key = themeNamespace + ":" + themePath;

        long now = System.currentTimeMillis();
        if (!key.equals(cachedKey) || now - cachedAt > 1000L) {
            cachedKey = key;
            cachedAt = now;
            cachedBackground = lookup(themeNamespace, themePath, BACKGROUND_SUFFIX);
            cachedFrame = lookup(themeNamespace, themePath, FRAME_SUFFIX);
        }
        if (cachedBackground == null && cachedFrame == null) {
            if (!key.equals(loggedKey)) {
                loggedKey = key;
                LOGGER.info("[tooltipfix] jade box sprites inactive: pack has no {}/{} for theme {}",
                        "tooltip/" + themePath + BACKGROUND_SUFFIX,
                        "tooltip/" + themePath + FRAME_SUFFIX, key);
            }
            return false;
        }
        if (!key.equals(loggedKey)) {
            loggedKey = key;
            LOGGER.info("[tooltipfix] jade box sprites ACTIVE (theme={} background={} frame={})",
                    key, describe(cachedBackground), describe(cachedFrame));
        }

        int spriteX = x - OVERHANG;
        int spriteY = y - OVERHANG;
        int spriteWidth = width + OVERHANG * 2;
        int spriteHeight = height + OVERHANG * 2;

        // 与 SlotSprites 同一个坑：取消原版绘制后，混合状态取决于调用点遗留（无颜色的 innerBlit
        // 既不 enableBlend 也不设因子），必须自己摆正并还原。
        boolean wasEnabled = BlendState.begin("jade box");
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        if (cachedBackground != null) {
            cachedBackground.draw(graphics, spriteX, spriteY, spriteWidth, spriteHeight);
        }
        if (cachedFrame != null) {
            cachedFrame.draw(graphics, spriteX, spriteY, spriteWidth, spriteHeight);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BlendState.end(wasEnabled);
        return true;
    }

    private static Theme currentTheme() {
        try {
            return IThemeHelper.get().theme();
        } catch (Throwable ignored) {
            // Jade 被别的 mod 拆掉/初始化失败时不要连累渲染
            return null;
        }
    }

    /**
     * 找 sprite：先按主题自己的命名空间找，再退回 {@code jade:}（自定义主题放在自己包里也想用 jade 的贴图）。
     */
    private static GuiSprite lookup(String themeNamespace, String themePath, String suffix) {
        GuiSprite sprite = tryLoad(themeNamespace, themePath, suffix);
        if (sprite == null && !JADE_NAMESPACE.equals(themeNamespace)) {
            sprite = tryLoad(JADE_NAMESPACE, themePath, suffix);
        }
        return sprite;
    }

    private static GuiSprite tryLoad(String namespace, String themePath, String suffix) {
        try {
            ResourceLocation id = new ResourceLocation(namespace, "tooltip/" + themePath + suffix);
            GuiSprite sprite = GuiSprite.of(id);
            if (sprite != null) {
                // CozyUI+ 的 jade 框是 400x400 画 100x100 逻辑画布（4 倍超采样），GUI scale 不是 4 的
                // 整数倍时 NEAREST 会把圆角抗锯齿像素抽掉、变成台阶。只给这几张点名的贴图开 GL_LINEAR。
                GuiSprite.markLinear(GuiSprite.textureOf(id));
            }
            return sprite;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describe(GuiSprite sprite) {
        return sprite == null ? "<missing>" : sprite.packId();
    }
}
