package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

/**
 * 创造模式物品栏顶部的标签页。
 *
 * <p>1.12.2 把 12 个标签页全画在一张 {@code textures/gui/container/creative_inventory/tabs.png}
 * 上，按 {@code u = column * 28} 取格子（{@code GuiContainerCreative.func_147051_a}）。但 CozyUI 的
 * 老式图**不是按 28 的格距排的**：实测它的每个标签只有 18.75 宽、间距 26（也就是 1.21 的排法），
 * 于是按 28 取样时第 3、4 列会正好切在两个标签中间——游戏里看到的就是「选中的标签变成一块更宽的
 * 浅灰块 + 右边多一条窄条 + 图标溢出」，这就是用户说的标签页错位。
 *
 * <p>1.21 已经改成一张一张独立 sprite（{@code tab_top_selected_1..7} 等，26x32），CozyUI 也提供了
 * 这套图，所以这里直接改用现代 sprite，按 1.12.2 给的位置画：格子仍然是 28 宽，把 26 宽的图居中放
 * （+1），图标由调用方画在 x+6，正好落在标签中间。
 */
public final class CreativeTabSprites {

    private static final ResourceLocation TABS_SHEET =
            new ResourceLocation("minecraft", "textures/gui/container/creative_inventory/tabs.png");

    /** 1.12.2 的 v 值 → 现代 sprite 名字里的状态，顺序与 {@code v / 32} 一致。 */
    private static final String[] KINDS = {
            "top_unselected", "top_selected", "bottom_unselected", "bottom_selected"
    };

    private CreativeTabSprites() {
    }

    /**
     * @return true 表示已经用材质包的 sprite 画过，调用方不要再画原版 quad
     */
    public static boolean blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        if (width != 28 || height != 32 || u < 0 || u % 28 != 0 || v < 0 || v > 96 || v % 32 != 0) {
            return false;
        }
        int column = u / 28;
        if (column > 6) {
            return false;
        }
        if (GuiRender.boundTexture() != tabsGl()) {
            return false;
        }
        GuiSprite sprite = GuiSprite.of(new ResourceLocation("minecraft",
                "container/creative_inventory/tab_" + KINDS[v / 32] + "_" + (column + 1)));
        if (sprite == null) {
            return false;
        }
        sprite.draw(x + 1, y, 26, 32, zLevel);
        return true;
    }

    private static int tabsGl() {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft == null) {
            return -1;
        }
        ITextureObject texture = minecraft.func_110434_K().func_110581_b(TABS_SHEET);
        return texture == null ? -1 : texture.func_110552_b();
    }
}
