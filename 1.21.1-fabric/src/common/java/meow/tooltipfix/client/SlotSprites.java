package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.21.2 的「材质包可自定义格子悬浮高亮框」。
 *
 * <p>1.21.1 的 {@code AbstractContainerScreen.renderSlotHighlight(GuiGraphics,int,int,int)} 只画一层固定颜色的
 * {@code fillGradient}；1.21.2 改成按 {@code container/slot_highlight_back} 与
 * {@code container/slot_highlight_front} 两个 GUI sprite 画（各自带 {@code gui.scaling} 九宫格描述），
 * 位置是 {@code (slot.x - 4, slot.y - 4, 24, 24)}。
 *
 * <p>这里借用 1.21.1 自带的 GUI sprite 图集：材质包把两个 PNG(+mcmeta) 放进
 * {@code assets/minecraft/textures/gui/sprites/container/} 就会被自动收录。图集里没有这两个 sprite 时返回 false，
 * 调用方回落原版填充色。
 */
public final class SlotSprites {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");

    /** 1.21.2 原版字段：SLOT_HIGHLIGHT_BACK / SLOT_HIGHLIGHT_FRONT。 */
    public static final ResourceLocation BACK = ResourceLocation.withDefaultNamespace("container/slot_highlight_back");
    public static final ResourceLocation FRONT = ResourceLocation.withDefaultNamespace("container/slot_highlight_front");

    /** 原版是 {@code slot.x - 4}，画 24x24（比 16x16 的格子每边多出 4 像素）。 */
    private static final int OVERHANG = 4;
    private static final int SIZE = 24;

    private static boolean loggedOnce = false;
    private static String lastState = "";

    private SlotSprites() {
    }

    /**
     * @return true = 已按材质包画过高亮框；false = 材质包没提供，调用方照原样画原版填充
     */
    public static boolean draw(GuiGraphics graphics, int x, int y, int z) {
        boolean hasBack = isAvailable(BACK);
        boolean hasFront = isAvailable(FRONT);
        // 1.21.2 的顺序是 back 在物品之前、front 在物品之后，两者都是「白色半透明蒙版 + 描边」。
        // 1.21.1 的高亮发生在物品画完之后，没有位置把 back 塞到物品下面，而 back 是半透明填充
        // （原版 alpha 96、CozyUI alpha 64），盖在物品上会把物品冲淡，所以有 front（描边框，中间透明）
        // 就只画 front，只有 back 时才退回画 back。
        ResourceLocation chosen = hasFront ? FRONT : (hasBack ? BACK : null);
        String state = "back=" + hasBack + " front=" + hasFront;
        if (!loggedOnce || !state.equals(lastState)) {
            loggedOnce = true;
            lastState = state;
            LOGGER.info("[tooltipfix] slot highlight sprites {}", chosen == null
                    ? "inactive, falling back to the stock fill"
                    : "ACTIVE (" + chosen + ")");
        }
        if (chosen == null) {
            return false;
        }
        // 1.21.1's sprite draw path manages no blending at all (only the coloured innerBlit overload does),
        // so without this the pack's antialiased border would be composited with whatever state the call
        // site left behind - normally blending off, which writes the border as an opaque black stroke.
        // Same fix shape as 1.20.1 1.0.16.
        BlendGuard.begin("slot highlight");
        graphics.blitSprite(chosen, x - OVERHANG, y - OVERHANG, z, SIZE, SIZE);
        BlendGuard.end();
        return true;
    }

    private static boolean isAvailable(ResourceLocation spriteId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getGuiSprites() == null) {
            return false;
        }
        TextureAtlasSprite sprite = minecraft.getGuiSprites().getSprite(spriteId);
        // TextureAtlas#getSprite hands back the missingno sprite for unknown ids.
        return sprite != null && !MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name());
    }
}
