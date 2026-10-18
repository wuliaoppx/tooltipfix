package meow.tooltipfix.client;

import java.nio.FloatBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * 1.12.2 的贴图原语。
 *
 * <p>被替换掉的 vanilla 方法（{@code Gui.drawTexturedModalRect} 等）不能再回调原版实现，所以「材质包没提供
 * sprite、照原样画」这一支必须自己复刻原版的 quad —— 参数、顶点顺序、z 值、GL 状态都照抄
 * {@code net.minecraft.client.gui.Gui} 的反编译结果，否则会出现偏移一个像素或混合不对的细微差别。
 *
 * <p>「当前绑定贴图」用 {@code GL_TEXTURE_BINDING_2D} 直接问 GL：调用方（vanilla）在调我们之前已经
 * bindTexture 过了，我们不必知道是哪个 ResourceLocation，只要不换绑就行。
 */
public final class GuiRender {

    /** 1.12.2 的 {@code Gui.drawTexturedModalRect} 写死的纹理归一化因子（1/256）。 */
    private static final float GUI_SHEET = 0.00390625F;

    private GuiRender() {
    }

    /**
     * 画我们自己的 sprite 贴图：源矩形 (u, v, srcW, srcH) 与目标矩形 (x, y, w, h) 是两个独立参数，
     * 归一化都除以 (texW, texH)（绘制空间，可能是 mcmeta 里的逻辑画布，也可能是纹理像素）。
     */
    public static void sprite(int glId, float x, float y, float w, float h,
                              float u, float v, float srcW, float srcH,
                              float texW, float texH, float z) {
        if (!spriteArgsValid(glId, w, h, srcW, srcH, texW, texH)) {
            return;
        }
        beginGui();
        spriteQuad(glId, x, y, w, h, u, v, srcW, srcH, texW, texH, z);
    }

    /**
     * 同 {@link #sprite}，但**不动混合函数**。
     *
     * <p>准星是 vanilla 用 {@code ONE_MINUS_DST_COLOR / ONE_MINUS_SRC_COLOR} 反色混合画的，
     * 材质包里的准星图是白色十字，靠反色才变成屏幕上的深色十字；一旦被我们改成普通 alpha 混合，
     * 准星就会突然变白。所以准星这一处只换贴图、不碰 blend。
     */
    public static void spriteKeepBlend(int glId, float x, float y, float w, float h,
                                       float u, float v, float srcW, float srcH,
                                       float texW, float texH, float z) {
        if (!spriteArgsValid(glId, w, h, srcW, srcH, texW, texH)) {
            return;
        }
        GlStateManager.func_179098_w();
        // 混合函数保持调用方的（反色），但**混合开关**必须确保是开的：原版画准星时只设 blendFunc，
        // 开不开 blend 全看前面 HUD 留下的状态；材质包覆盖准星/攻击指示器后我们必须自己保证。
        GlStateManager.func_179147_l();
        GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
        spriteQuad(glId, x, y, w, h, u, v, srcW, srcH, texW, texH, z);
    }

    private static boolean spriteArgsValid(int glId, float w, float h,
                                           float srcW, float srcH, float texW, float texH) {
        return w > 0 && h > 0 && srcW > 0 && srcH > 0 && texW > 0 && texH > 0 && glId > 0;
    }

    private static void spriteQuad(int glId, float x, float y, float w, float h,
                                   float u, float v, float srcW, float srcH,
                                   float texW, float texH, float z) {
        GlStateManager.func_179144_i(glId);
        float u0 = u / texW;
        float v0 = v / texH;
        float u1 = (u + srcW) / texW;
        float v1 = (v + srcH) / texH;
        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        buffer.func_181668_a(7, DefaultVertexFormats.field_181707_g);
        buffer.func_181662_b(x, y + h, z).func_187315_a(u0, v1).func_181675_d();
        buffer.func_181662_b(x + w, y + h, z).func_187315_a(u1, v1).func_181675_d();
        buffer.func_181662_b(x + w, y, z).func_187315_a(u1, v0).func_181675_d();
        buffer.func_181662_b(x, y, z).func_187315_a(u0, v0).func_181675_d();
        tessellator.func_78381_a();
    }

    /** {@code Gui.drawTexturedModalRect(int,int,int,int,int,int)} 的等价实现（用当前绑定的贴图）。 */
    public static void rawBlit(float x, float y, float u, float v, float w, float h, float z) {
        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        buffer.func_181668_a(7, DefaultVertexFormats.field_181707_g);
        buffer.func_181662_b(x, y + h, z).func_187315_a(u * GUI_SHEET, (v + h) * GUI_SHEET).func_181675_d();
        buffer.func_181662_b(x + w, y + h, z).func_187315_a((u + w) * GUI_SHEET, (v + h) * GUI_SHEET).func_181675_d();
        buffer.func_181662_b(x + w, y, z).func_187315_a((u + w) * GUI_SHEET, v * GUI_SHEET).func_181675_d();
        buffer.func_181662_b(x, y, z).func_187315_a(u * GUI_SHEET, v * GUI_SHEET).func_181675_d();
        tessellator.func_78381_a();
    }

    /** {@code Gui.drawModalRectWithCustomSizedTexture} 的等价实现（z 固定 0）。 */
    public static void rawCustom(float x, float y, float u, float v, float w, float h, float texW, float texH) {
        float fx = 1.0F / texW;
        float fy = 1.0F / texH;
        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        buffer.func_181668_a(7, DefaultVertexFormats.field_181707_g);
        buffer.func_181662_b(x, y + h, 0.0D).func_187315_a(u * fx, (v + h) * fy).func_181675_d();
        buffer.func_181662_b(x + w, y + h, 0.0D).func_187315_a((u + w) * fx, (v + h) * fy).func_181675_d();
        buffer.func_181662_b(x + w, y, 0.0D).func_187315_a((u + w) * fx, v * fy).func_181675_d();
        buffer.func_181662_b(x, y, 0.0D).func_187315_a(u * fx, v * fy).func_181675_d();
        tessellator.func_78381_a();
    }

    /** {@code Gui.drawScaledCustomSizeModalRect} 的等价实现（z 固定 0）。 */
    public static void rawScaled(float x, float y, float u, float v, float srcW, float srcH,
                                 float w, float h, float texW, float texH) {
        float fx = 1.0F / texW;
        float fy = 1.0F / texH;
        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        buffer.func_181668_a(7, DefaultVertexFormats.field_181707_g);
        buffer.func_181662_b(x, y + h, 0.0D).func_187315_a(u * fx, (v + srcH) * fy).func_181675_d();
        buffer.func_181662_b(x + w, y + h, 0.0D).func_187315_a((u + srcW) * fx, (v + srcH) * fy).func_181675_d();
        buffer.func_181662_b(x + w, y, 0.0D).func_187315_a((u + srcW) * fx, v * fy).func_181675_d();
        buffer.func_181662_b(x, y, 0.0D).func_187315_a(u * fx, v * fy).func_181675_d();
        tessellator.func_78381_a();
    }

    /** {@code Gui.drawGradientRect} 的等价实现（含它那套 GL 状态进出）。 */
    public static void rawGradient(int left, int top, int right, int bottom, int colorFrom, int colorTo, float z) {
        float a1 = (float) (colorFrom >> 24 & 0xFF) / 255.0F;
        float r1 = (float) (colorFrom >> 16 & 0xFF) / 255.0F;
        float g1 = (float) (colorFrom >> 8 & 0xFF) / 255.0F;
        float b1 = (float) (colorFrom & 0xFF) / 255.0F;
        float a2 = (float) (colorTo >> 24 & 0xFF) / 255.0F;
        float r2 = (float) (colorTo >> 16 & 0xFF) / 255.0F;
        float g2 = (float) (colorTo >> 8 & 0xFF) / 255.0F;
        float b2 = (float) (colorTo & 0xFF) / 255.0F;
        GlStateManager.func_179090_x();
        GlStateManager.func_179147_l();
        GlStateManager.func_179118_c();
        GlStateManager.func_187428_a(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.func_179103_j(7425);
        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        buffer.func_181668_a(7, DefaultVertexFormats.field_181706_f);
        buffer.func_181662_b(right, top, z).func_181666_a(r1, g1, b1, a1).func_181675_d();
        buffer.func_181662_b(left, top, z).func_181666_a(r1, g1, b1, a1).func_181675_d();
        buffer.func_181662_b(left, bottom, z).func_181666_a(r2, g2, b2, a2).func_181675_d();
        buffer.func_181662_b(right, bottom, z).func_181666_a(r2, g2, b2, a2).func_181675_d();
        tessellator.func_78381_a();
        GlStateManager.func_179103_j(7424);
        GlStateManager.func_179084_k();
        GlStateManager.func_179141_d();
        GlStateManager.func_179098_w();
    }

    /** {@code Gui.drawRect} 的等价实现（含它那套 GL 状态进出）。 */
    public static void rawRect(int left, int top, int right, int bottom, int color) {
        int x1 = left;
        int x2 = right;
        int y1 = top;
        int y2 = bottom;
        if (x1 < x2) {
            int swap = x1;
            x1 = x2;
            x2 = swap;
        }
        if (y1 < y2) {
            int swap = y1;
            y1 = y2;
            y2 = swap;
        }
        float a = (float) (color >> 24 & 0xFF) / 255.0F;
        float r = (float) (color >> 16 & 0xFF) / 255.0F;
        float g = (float) (color >> 8 & 0xFF) / 255.0F;
        float b = (float) (color & 0xFF) / 255.0F;
        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        GlStateManager.func_179147_l();
        GlStateManager.func_179090_x();
        GlStateManager.func_187428_a(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.func_179131_c(r, g, b, a);
        buffer.func_181668_a(7, DefaultVertexFormats.field_181705_e);
        buffer.func_181662_b(x1, y2, 0.0D).func_181675_d();
        buffer.func_181662_b(x2, y2, 0.0D).func_181675_d();
        buffer.func_181662_b(x2, y1, 0.0D).func_181675_d();
        buffer.func_181662_b(x1, y1, 0.0D).func_181675_d();
        tessellator.func_78381_a();
        GlStateManager.func_179098_w();
        GlStateManager.func_179084_k();
    }

    /**
     * 画我们自己的贴图前摆好 GL 状态：开贴图 + 开混合 + 复位混合因子 + 白色。
     *
     * <p>进入时的 GL 状态是调用方留下的（例如 {@code drawGradientRect} 之后「混合关、贴图开」），
     * 这里把 sprite 需要的那几项摆好；调用方原来的状态由 {@link #snapshot()} /
     * {@link #restore(GlSnapshot)} 在外层还原。
     *
     * <p>不动光照：GUI 从头到尾都在「关光照」的 2D 状态里画，碰它只会给别的渲染层留坑。
     */
    public static void beginGui() {
        GlStateManager.func_179098_w();
        GlStateManager.func_179147_l();
        GlStateManager.func_187428_a(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 一份「我们可能改到的 GL 状态」快照。
     *
     * <p>为什么必须还原：原版 {@code Gui.drawTexturedModalRect} 一个 GL 状态都不改，我们把它换成
     * 「画 sprite」就等于在 vanilla 完全没预料的地方改了混合开关和当前颜色。最典型的翻车场面：
     * HUD 里 {@code GlStateManager.color(1,1,1,alpha)} 之后连着画同一块东西好几遍做淡出，第一遍走了
     * sprite、颜色被复位成不透明白色，后面几遍立刻变实心。
     */
    public static final class GlSnapshot {
        final boolean blend;
        final boolean texture2d;
        final int blendSrc;
        final int blendDst;
        final float red;
        final float green;
        final float blue;
        final float alpha;

        GlSnapshot(boolean blend, boolean texture2d, int blendSrc, int blendDst,
                   float red, float green, float blue, float alpha) {
            this.blend = blend;
            this.texture2d = texture2d;
            this.blendSrc = blendSrc;
            this.blendDst = blendDst;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }
    }

    public static GlSnapshot snapshot() {
        // LWJGL 2 的 glGetFloat(int, FloatBuffer) 内部按「最多 16 个元素」做检查，
        // 传 4 个容量的 buffer 会直接抛 IllegalArgumentException:「Number of remaining
        // buffer elements is 4, must be at least 16」。所以这里必须给够 16 格。
        FloatBuffer color = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        return new GlSnapshot(
                GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_TEXTURE_2D),
                GL11.glGetInteger(GL11.GL_BLEND_SRC),
                GL11.glGetInteger(GL11.GL_BLEND_DST),
                color.get(0), color.get(1), color.get(2), color.get(3));
    }

    /** 把 {@link #snapshot()} 抓到的状态原样装回去（全部走 GlStateManager，保证它的缓存同步）。 */
    public static void restore(GlSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        GlStateManager.func_179112_b(snapshot.blendSrc, snapshot.blendDst);
        if (snapshot.blend) {
            GlStateManager.func_179147_l();
        } else {
            GlStateManager.func_179084_k();
        }
        GlStateManager.func_179131_c(snapshot.red, snapshot.green, snapshot.blue, snapshot.alpha);
        if (snapshot.texture2d) {
            GlStateManager.func_179098_w();
        } else {
            GlStateManager.func_179090_x();
        }
    }

    /** 当前绑定的 2D 贴图 id（passthrough 复刻原版 quad 时用，避免改变绑定）。 */
    public static int boundTexture() {
        return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    }

    /**
     * 把 {@code GL_TEXTURE_2D} 还原成之前绑定的那张。
     *
     * <p>原版 GUI 的常见写法是「bindTexture 一次 → 连续 drawTexturedModalRect 好几张子图」
     * （药水效果背景 + 8 个图标、背包面板 + 数字等）。我们插进去换成自己的 sprite 时会把绑定改到
     * 自己的贴图上，原版调用方不会重绑，于是后面那张子图就采到我们的贴图——药水图标显示成几条横线
     * 就是这么来的。所以每个 sprite 画完必须把绑定还原。
     */
    public static void rebind(int glTexture) {
        if (glTexture > 0 && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != glTexture) {
            GlStateManager.func_179144_i(glTexture);
        }
    }
}
