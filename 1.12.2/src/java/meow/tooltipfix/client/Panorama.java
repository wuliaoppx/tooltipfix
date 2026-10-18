package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

/**
 * 主菜单全景图，按屏幕原生分辨率直接渲染。
 *
 * <p><b>原版 1.12.2 为什么糊：</b>{@code GuiMainMenu.func_73971_c} 先把 6 面立方体画进一个
 * 256x256 的视口，再用 {@code func_73968_a} 反复把屏幕内容 {@code glCopyTexSubImage2D} 回一张
 * 256x256 贴图、以 1、1/2、1/3 的 alpha 叠 3 次（一次调用 3 层、连调 7 次 = 21 层），最后只取这张
 * 贴图中间 {@code 120/max(w,h)} 那一小块（1920 宽时约 240x135）拉伸铺满全屏；此外那 64 层叠加
 * 本身还带偏移柔焦。材质包里 1024x1024 的原图实际只贡献 240x135 的信息量。
 *
 * <p><b>这里的实现：</b>照 1.21.2 的 {@code CubeMap}（混淆名 {@code gkv}）逐行移植 ——
 * 透视 85°（{@code new Matrix4f().setPerspective(1.4835298f, aspect, 0.05f, 10f)}）、
 * 先 {@code rotateX(π)}、再 {@code rotateX(10°)} + {@code rotateY(-spin)}（{@code PanoramaRenderer}
 * 每帧 {@code spin += delta*0.1}），6 个面的顶点与 UV 也照抄它，所以取景和 1.21.2 完全一致。
 * 唯一差别是不叠那 4 遍 alpha 混合（1.21.2 是 255/127/85/63 四遍自混合），只画一遍，得到最清晰的画面。
 */
public final class Panorama {

    /** 6 个面，路径与 1.12.2 的 {@code field_73978_o} / 1.21.2 的 CubeMap 完全一致。 */
    private static final ResourceLocation[] FACES = new ResourceLocation[6];

    /** 1.21.2 CubeMap 的顶点表：每面 4 个顶点，顺序 (x, y, z, u, v)。 */
    private static final float[][] QUADS = {
            // +Z
            {-1, -1, 1, 0, 0, -1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, -1, 1, 1, 0},
            // +X
            {1, -1, 1, 0, 0, 1, 1, 1, 0, 1, 1, 1, -1, 1, 1, 1, -1, -1, 1, 0},
            // -Z
            {1, -1, -1, 0, 0, 1, 1, -1, 0, 1, -1, 1, -1, 1, 1, -1, -1, -1, 1, 0},
            // -X
            {-1, -1, -1, 0, 0, -1, 1, -1, 0, 1, -1, 1, 1, 1, 1, -1, -1, 1, 1, 0},
            // -Y（底面）
            {-1, -1, -1, 0, 0, -1, -1, 1, 0, 1, 1, -1, 1, 1, 1, 1, -1, -1, 1, 0},
            // +Y（顶面）
            {-1, 1, 1, 0, 0, -1, 1, -1, 0, 1, 1, 1, -1, 1, 1, 1, 1, 1, 1, 0},
    };

    /** 1.21.2 PanoramaRenderer 的 spin：每帧 += delta*0.1 度。 */
    private static float spin;

    /** 1.21.2 CubeMap 的固定俯仰角（度）。 */
    private static final float PITCH = 10.0F;

    static {
        for (int i = 0; i < 6; i++) {
            FACES[i] = new ResourceLocation("minecraft", "textures/gui/title/background/panorama_" + i + ".png");
        }
    }

    private Panorama() {
    }

    /** 资源包重载时清掉旋转累积，避免重载后构图跳变。 */
    public static void reset() {
        spin = 0.0F;
    }

    /**
     * 替换 {@code GuiMainMenu.func_73971_c(int,int,float)} 的整个方法体。
     *
     * @param partialTicks 原方法的第三个参数（渲染插值），用于让旋转平滑
     */
    public static void render(float partialTicks) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null) {
            return;
        }
        int width = mc.field_71443_c;
        int height = mc.field_71440_d;
        if (width <= 0 || height <= 0) {
            return;
        }
        spin += partialTicks * 0.1F;
        if (spin > 360.0F) {
            spin -= 360.0F;
        }

        // 注意：这里**不能**解绑主帧缓冲。原版之所以解绑，是因为它要把屏幕内容回读进贴图、
        // 再贴回主帧缓冲；我们直接画，就必须画在当帧的主帧缓冲里，否则帧末 FBO→屏幕的 blit
        // 会把它整块覆盖掉（表现为背景一片空白）。
        GlStateManager.func_179083_b(0, 0, width, height);

        GlStateManager.func_179128_n(GL11.GL_PROJECTION);
        GlStateManager.func_179094_E();
        GlStateManager.func_179096_D();
        // 85°（= 1.4835298 弧度），与 1.21.2 的 CubeMap 一致。
        Project.gluPerspective(85.0F, (float) width / (float) height, 0.05F, 10.0F);
        GlStateManager.func_179128_n(GL11.GL_MODELVIEW);
        GlStateManager.func_179094_E();
        GlStateManager.func_179096_D();
        GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.func_179114_b(180.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.func_179147_l();
        GlStateManager.func_179118_c();
        GlStateManager.func_179129_p();
        GlStateManager.func_179132_a(false);
        GlStateManager.func_187428_a(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        GlStateManager.func_179114_b(PITCH, 1.0F, 0.0F, 0.0F);
        GlStateManager.func_179114_b(-spin, 0.0F, 1.0F, 0.0F);

        Tessellator tessellator = Tessellator.func_178181_a();
        BufferBuilder buffer = tessellator.func_178180_c();
        for (int face = 0; face < 6; face++) {
            mc.func_110434_K().func_110577_a(FACES[face]);
            float[] quad = QUADS[face];
            buffer.func_181668_a(GL11.GL_QUADS, DefaultVertexFormats.field_181709_i);
            for (int v = 0; v < 4; v++) {
                int o = v * 5;
                buffer.func_181662_b(quad[o], quad[o + 1], quad[o + 2])
                        .func_187315_a(quad[o + 3], quad[o + 4])
                        .func_181669_b(255, 255, 255, 255).func_181675_d();
            }
            tessellator.func_78381_a();
        }

        GlStateManager.func_179128_n(GL11.GL_PROJECTION);
        GlStateManager.func_179121_F();
        GlStateManager.func_179128_n(GL11.GL_MODELVIEW);
        GlStateManager.func_179121_F();
        GlStateManager.func_179132_a(true);
        GlStateManager.func_179089_o();
        GlStateManager.func_179126_j();

        GlStateManager.func_179083_b(0, 0, width, height);
    }
}
