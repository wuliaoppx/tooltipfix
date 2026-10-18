package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

/**
 * 药水效果背景（HUD 右上角的图标框 + 物品栏左侧的效果列表）与效果图标。
 *
 * <p>1.12.2 这几处都从 {@code textures/gui/container/inventory.png} 抠图：HUD 是 (141,166) 与
 * (165,166) 两个 24x24（差在 {@code potionEffect.func_82720_e()} 的环境效果分支），物品栏列表是
 * (0,166) 的 140x32（1.21 是 120x32，1.12.2 的面板更宽），每个效果图标是 (u%8*18, 198+u/8*18) 的 18x18。
 */
public final class EffectSprites {

    private static final ResourceLocation HUD_BACKGROUND =
            new ResourceLocation("minecraft", "hud/effect_background");
    private static final ResourceLocation HUD_BACKGROUND_AMBIENT =
            new ResourceLocation("minecraft", "hud/effect_background_ambient");
    /** 列表背景只有一份（1.12.2 没有 small 版分支），按 140x32 整体拉伸。 */
    private static final ResourceLocation LIST_BACKGROUND =
            new ResourceLocation("minecraft", "container/inventory/effect_background_large");

    private static final ResourceLocation INVENTORY_SHEET =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");
    private static final String INVENTORY_SHEET_PATH = "textures/gui/container/inventory.png";

    /** 诊断用：只打前 8 次没命中，避免刷屏。 */
    private static int probes;

    private EffectSprites() {
    }

    /**
     * @return true 表示已经用材质包的 sprite 画过，调用方不要再去画原版 quad
     */
    public static boolean blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (potionIcon(zLevel, x, y, u, v, width, height)) {
            return true;
        }
        ResourceLocation spriteId;
        if (u == 141 && v == 166 && width == 24 && height == 24) {
            spriteId = HUD_BACKGROUND;
        } else if (u == 165 && v == 166 && width == 24 && height == 24) {
            spriteId = HUD_BACKGROUND_AMBIENT;
        } else if (u == 0 && v == 166 && width == 140 && height == 32) {
            spriteId = LIST_BACKGROUND;
        } else {
            return false;
        }
        GuiSprite sprite = GuiSprite.of(spriteId);
        if (sprite == null) {
            return false;
        }
        if (spriteId == LIST_BACKGROUND) {
            // 1.12.2 的列表底板是 140x32，1.21.2 的贴图只有 120x32（多出来的 20 是 1.21 去掉的右边距）。
            // 直接拉成 140 会把圆角和中缝一起拉扁，看起来就是用户说的「药水效果有错位」；
            // 按贴图自身宽高比（120:32）画、左对齐，正好和材质包自带旧版位图里那张 120 宽卡片重合。
            int natural = Math.round(height * (float) sprite.frameWidth() / Math.max(1, sprite.frameHeight()));
            int drawWidth = natural > 0 && natural < width ? natural : width;
            sprite.draw(x, y, drawWidth, height, zLevel);
            return true;
        }
        sprite.draw(x, y, width, height, zLevel);
        return true;
    }

    /**
     * 药水效果图标（1.12.2 的 {@code InventoryEffectRenderer} 里那 8x3 个 18x18）。
     *
     * <p>材质包没有这块内容：1.21 的效果图标改成单独贴图，CozyUI 把 inventory.png 的图标网格换成了
     * 圆角方框，1.12.2 照着 UV 抠出来就是空白/错位。所以这里**强制回落到游戏 jar 自带的
     * inventory.png**，用我们自己的贴图重画同一块矩形（1:1 裁剪，不缩放）。
     */
    private static boolean potionIcon(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (width != 18 || height != 18 || v < 198 || u < 0 || u >= 144 || (u % 18) != 0) {
            return false;
        }
        int bound = GuiRender.boundTexture();
        int sheetGl = inventorySheetGl();
        if (bound != sheetGl) {
            if (probes++ < 8) {
                Debug.info("potionIcon miss u=" + u + " v=" + v + " bound=" + bound + " ("
                        + Debug.textureName(bound) + ") sheet=" + sheetGl);
            }
            return false;
        }
        GuiSprite sheet = GuiSprite.ofVanilla(INVENTORY_SHEET_PATH);
        if (sheet == null) {
            return false;
        }
        sheet.drawRegion(x, y, width, height, u, v, 256, 256, zLevel);
        return true;
    }

    /** vanilla 那张 inventory.png 的 GL id（没加载或材质包重载后为 -1）。 */
    private static int inventorySheetGl() {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft == null) {
            return -1;
        }
        ITextureObject texture = minecraft.func_110434_K().func_110581_b(INVENTORY_SHEET);
        return texture == null ? -1 : texture.func_110552_b();
    }
}
