package meow.tooltipfix.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 物品提示框（tooltip）的底 + 描边。
 *
 * <p>1.12.2 的 {@code GuiScreen.drawHoveringText} 没有「画一块底图」的概念，它是用 9 次
 * {@code drawGradientRect} 拼出来的一个 1px 描边 + 半透明底。1.21.2 改成两张九宫格 sprite
 * （{@code tooltip/background} + {@code tooltip/frame}），这正是材质包能自定义悬浮框的机制。
 *
 * <p>本类由 {@link Hooks#tooltip} 调用：{@code GuiScreen.drawHoveringText} 的整个方法体已被
 * coremod 换掉，所以这里要自己复刻原版的几何（{@code x+12 / y-12}、{@code 8 + 2 + (n-1)*10}、
 * 越界回推）、GL 状态（关光照/关深度）和文本绘制，否则视觉会和原版对不上。
 *
 * <p>为什么不再「嗅探 9 次渐变」：实测那 9 次调用走的是未映射的
 * {@code invokevirtual GuiScreen.a(IIIIII)V}，进不到被补丁过的 {@code Gui.func_73733_a}，
 * 攒着永远等不到最后一次，tooltip 于是一直是原版样式（用户报的「物品悬浮框没有变化」）。
 */
public final class TooltipSprites {

    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");

    /** 原版 {@code 0xF0100010}：tooltip 底色。 */
    private static final int BACKGROUND_COLOR = -267386864;
    /** 原版 {@code 0x505000FF}：描边上端色。 */
    private static final int FRAME_TOP_COLOR = 1347420415;
    /** 原版 {@code l2 = (k2 & 0x00FF00FF) >> 1 | k2 & 0xFF000000} = 0x5028007F：描边下端色。 */
    private static final int FRAME_BOTTOM_COLOR = 1344798847;

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation("minecraft", "tooltip/background");
    private static final ResourceLocation FRAME =
            new ResourceLocation("minecraft", "tooltip/frame");

    /**
     * 材质包 tooltip 图往外扩的量。
     *
     * <p>{@code tooltip/background} 是 60x60 画布 + border=16 的九宫格，画布里自带一圈阴影/圆角；
     * 原版矩形本身已经包含了 4px 的描边余量，所以再补 12px 正好让边框按 1:1 展开。
     */
    private static final int SPRITE_INSET = 12;

    /** 原版 tooltip 用 zLevel=300 画，保证压在物品栏、物品图标之上。 */
    private static final float TOOLTIP_Z = 300.0F;

    private static GuiSprite backgroundSprite;
    private static GuiSprite frameSprite;
    private static boolean loggedOnce;

    private TooltipSprites() {
    }

    /**
     * 直接接管一次 tooltip 绘制（{@code GuiScreen.func_146283_a} 的注入目标）。
     *
     * <p>几何、GL 状态、文本绘制全部照 1.12.2 反编译逐字复刻，只是把「9 次渐变拼出来的框」
     * 换成材质包的两张 sprite；材质包没给图时仍然按原版渐变画（{@link #drawVanillaBackground}）。
     */
    public static void draw(GuiScreen screen, List<String> lines, int mouseX, int mouseY) {
        draw(screen, lines, mouseX, mouseY, null);
    }

    /**
     * @param hint 调用方指定的字体渲染器（Forge 的
     *             {@code drawHoveringText(List,int,int,FontRenderer)} 会传物品自定义的那个），
     *             {@code null} 时退回主字体
     */
    public static void draw(GuiScreen screen, List<String> lines, int mouseX, int mouseY, FontRenderer hint) {
        render(screen, lines, mouseX, mouseY, hint, false);
    }

    /**
     * 兜底：{@link Hooks#tooltip} 里抛异常时用它画一个「原版样式」的 tooltip。
     *
     * <p>宁可掉回原版外观，也不能让悬浮框整个消失 —— {@code drawHoveringText} 的方法体已经被
     * coremod 换掉了，这里不画就真的什么都没有。
     */
    public static void drawFallback(GuiScreen screen, List<String> lines, int mouseX, int mouseY,
                                    FontRenderer hint) {
        render(screen, lines, mouseX, mouseY, hint, true);
    }

    private static void render(GuiScreen screen, List<String> lines, int mouseX, int mouseY,
                               FontRenderer hint, boolean forceVanilla) {
        if (screen == null || lines == null || lines.isEmpty()) {
            return;
        }
        // GuiScreen.field_146289_q（fontRenderer）是 protected，这里取同一个实例的公开入口。
        Minecraft mc = Minecraft.func_71410_x();
        FontRenderer font = hint != null ? hint : (mc == null ? null : mc.field_71466_p);
        if (font == null) {
            return;
        }
        int textWidth = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            int before = HdFont.calls();
            int width = font.func_78256_a(line);
            int calls = HdFont.calls() - before;
            Debug.once("measure|" + line, "tooltip measure '" + line + "' gui=" + width
                    + " hd=" + HdFont.stringWidth(line) + " adv_calls=" + calls
                    + " avail=" + HdFont.available());
            if (width > textWidth) {
                textWidth = width;
            }
        }
        int left = mouseX + 12;
        int top = mouseY - 12;
        // 1.12.2 把行距写死 10、单行框高写死 8。HD 字体（材质包 mcsans height=12/ascent=9）
        // 的字形从 y-2 画到 y+10，照原版算就会「第二行压在第一行的降部上 + 顶出框的下边界」。
        int step = HdFont.lineStep();
        int height = HdFont.available() ? HdFont.lineHeight() : 8;
        if (lines.size() > 1) {
            height += 2 + (lines.size() - 1) * step;
        }
        Debug.once("layout|" + lines.size() + "|" + textWidth,
                "tooltip layout lines=" + lines.size() + " textWidth=" + textWidth
                        + " left=" + left + " top=" + top + " height=" + height
                        + " step=" + step + " screen=" + screen.field_146294_l + "x" + screen.field_146295_m);
        if (left + textWidth > screen.field_146294_l) {
            left -= 28 + textWidth;
        }
        if (top + height + 6 > screen.field_146295_m) {
            top = screen.field_146295_m - height - 6;
        }
        if (top < 4) {
            top = 4;
        }

        // 原版序言/尾声：tooltip 关光照、关深度，让框永远压在物品栏之上
        GlStateManager.func_179101_C();
        RenderHelper.func_74518_a();
        GlStateManager.func_179140_f();
        GlStateManager.func_179097_i();
        try {
            GuiSprite background = null;
            GuiSprite frame = null;
            if (forceVanilla || Debug.passthrough) {
                background = null;
                frame = null;
            } else {
                try {
                    background = background();
                    frame = frame();
                } catch (Throwable t) {
                    Debug.info("tooltip sprite load failed: " + t);
                }
            }
            if (background == null && frame == null) {
                if (!Debug.passthrough) {
                    Debug.info("[tooltipfix] tooltip sprite missing, vanilla fallback ("
                            + GuiSprite.of(BACKGROUND) + " / " + GuiSprite.of(FRAME) + ")");
                }
                drawVanillaBackground(left, top, textWidth, height);
            } else {
                int x = left - 4 - SPRITE_INSET;
                int y = top - 4 - SPRITE_INSET;
                int width = textWidth + 8 + SPRITE_INSET * 2;
                int boxHeight = height + 8 + SPRITE_INSET * 2;
                try {
                    if (background != null) {
                        background.draw(x, y, width, boxHeight, TOOLTIP_Z);
                    }
                    if (frame != null) {
                        frame.draw(x, y, width, boxHeight, TOOLTIP_Z);
                    }
                } catch (Throwable t) {
                    Debug.info("tooltip sprite draw failed: " + t);
                    drawVanillaBackground(left, top, textWidth, height);
                }
            }
            boolean hd = HdFont.available();
            int lineY = top;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line != null) {
                    font.func_175063_a(line, (float) left, (float) lineY, -1);
                }
                if (hd) {
                    lineY += step;
                } else {
                    if (i == 0) {
                        lineY += 2;
                    }
                    lineY += 10;
                }
            }
            logOnce(background, frame);
        } finally {
            GlStateManager.func_179145_e();
            GlStateManager.func_179126_j();
            RenderHelper.func_74519_b();
            GlStateManager.func_179091_B();
        }
    }

    /** 材质包没有 tooltip 图时，按原版的 9 次渐变原样重画（1.12.2 的几何逐字复刻）。 */
    private static void drawVanillaBackground(int left, int top, int width, int height) {
        int right = left + width;
        int bottom = top + height;
        int background = BACKGROUND_COLOR;
        GuiRender.rawGradient(left - 3, top - 4, right + 3, top - 3, background, background, TOOLTIP_Z);
        GuiRender.rawGradient(left - 3, bottom + 3, right + 3, bottom + 4, background, background, TOOLTIP_Z);
        GuiRender.rawGradient(left - 3, top - 3, right + 3, bottom + 3, background, background, TOOLTIP_Z);
        GuiRender.rawGradient(left - 4, top - 3, left - 3, bottom + 3, background, background, TOOLTIP_Z);
        GuiRender.rawGradient(right + 3, top - 3, right + 4, bottom + 3, background, background, TOOLTIP_Z);
        int frameTop = FRAME_TOP_COLOR;
        int frameBottom = FRAME_BOTTOM_COLOR;
        GuiRender.rawGradient(left - 3, top - 2, left - 2, bottom + 2, frameTop, frameBottom, TOOLTIP_Z);
        GuiRender.rawGradient(right + 2, top - 2, right + 3, bottom + 2, frameBottom, frameTop, TOOLTIP_Z);
        GuiRender.rawGradient(left - 3, top - 3, right + 3, top - 2, frameTop, frameTop, TOOLTIP_Z);
        GuiRender.rawGradient(left - 3, bottom + 2, right + 3, bottom + 3, frameBottom, frameBottom, TOOLTIP_Z);
    }

    private static GuiSprite background() {
        if (backgroundSprite == null) {
            backgroundSprite = GuiSprite.of(BACKGROUND);
        }
        return backgroundSprite;
    }

    private static GuiSprite frame() {
        if (frameSprite == null) {
            frameSprite = GuiSprite.of(FRAME);
        }
        return frameSprite;
    }

    /** 资源包重载后 sprite 贴图 id 会变，缓存的实例必须跟着丢。 */
    public static void reset() {
        backgroundSprite = null;
        frameSprite = null;
    }

    private static void logOnce(GuiSprite background, GuiSprite frame) {
        if (loggedOnce) {
            return;
        }
        loggedOnce = true;
        LOGGER.info("[tooltipfix] tooltip sprites ACTIVE (background pack={}, frame pack={})",
                background == null ? "<missing>" : background.packId(),
                frame == null ? "<missing>" : frame.packId());
    }
}
