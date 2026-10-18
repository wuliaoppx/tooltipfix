package meow.tooltipfix.client;

import net.minecraft.util.ResourceLocation;

/**
 * 1.12.2 老式 GUI 图集的「尺寸自动识别」。
 *
 * <p><b>问题：</b>1.12.2 的 {@code Gui.func_73729_b} 把 UV 写死成 1/256
 * （{@code GuiRender.rawBlit} 里的 {@code GUI_SHEET = 0.00390625F}），因为原版所有 GUI 图集都是
 * 256x256。而 CozyUI 这类现代材质包把 {@code icons.png} / {@code widgets.png} /
 * {@code container/*.png} 直接按 4 倍重画（1024x1024）。于是同一个 UV 在 1024 的图上只取到左上角
 * 1/4 —— 表现就是「心/饥饿/盔甲/空气/准星/攻击冷却指示器」全部错位、只剩一块放大的碎片，
 * 看起来很像「不支持阿尔法通道」。
 *
 * <p><b>做法：</b>不猜、也不写死 4 倍。运行时比一次「材质包里这张图多大」和「游戏 jar 里这张图多大」，
 * 把源矩形按比例换算过去；两者一样大就直接放行给原版路径（零开销）。材质包没提供这张图时
 * {@link GuiSprite#ofTexture} 读到的是 jar 里那份，比例同样是 1:1，自然退回原版。
 *
 * <p>这样连 {@code container/villager.png}（原版 512x256、包里 2048x1024，横纵比例不同）也能对上。
 */
public final class LegacySheets {

    private LegacySheets() {
    }

    /**
     * @return true = 已经用换算后的源矩形画完了；false = 交给调用方走原版路径
     */
    public static boolean blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (width <= 0 || height <= 0 || u < 0 || v < 0) {
            return false;
        }
        ResourceLocation texture = Debug.textureLocation(GuiRender.boundTexture());
        if (texture == null) {
            return false;
        }
        String path = texture.func_110623_a();
        // 只处理 assets/minecraft/textures/gui/ 下的老式图集；现代 sprite（textures/gui/sprites/）
        // 有自己的九宫格加载器，不走这里。
        if (!path.startsWith("textures/gui/") || !path.endsWith(".png")
                || path.startsWith("textures/gui/sprites/")) {
            return false;
        }
        GuiSprite vanilla = GuiSprite.ofVanilla(path);
        if (vanilla == null) {
            return false;
        }
        int vanillaWidth = vanilla.frameWidth();
        int vanillaHeight = vanilla.frameHeight();
        if (vanillaWidth <= 0 || vanillaHeight <= 0) {
            return false;
        }
        // 超出原版图集范围的 blit（例如某些 mod 用负坐标/超界 UV 做平铺）不碰
        if (u + width > vanillaWidth || v + height > vanillaHeight) {
            return false;
        }
        GuiSprite pack = GuiSprite.ofTexture(texture);
        if (pack == null) {
            return false;
        }
        // 容器底图被材质包换过 -> 记下这个界面，容器标题文字由 TitleFilter 吞掉（1.13+ 不画标题）
        if (path.startsWith("textures/gui/container/") && !"Default".equals(pack.packId())) {
            TitleFilter.noteCovered();
        }
        if (pack.frameWidth() == vanillaWidth && pack.frameHeight() == vanillaHeight) {
            return false; // 1:1，交给原版更快
        }
        pack.drawRegion(x, y, width, height, u, v, vanillaWidth, vanillaHeight, zLevel);
        return true;
    }
}
