package meow.tooltipfix.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the GUI atlas sprite draws independent of whatever blending state the call site left behind.
 *
 * <p>javap evidence (1.21.1 named {@code net.minecraft.client.gui.GuiGraphics}): the only place in that
 * whole class that touches blending is the <em>coloured</em> 14-argument
 * {@code innerBlit(ResourceLocation,IIIIIFFFF,FFFF)}, which calls {@code RenderSystem.enableBlend()} and
 * {@code disableBlend()}. The uncoloured 10-argument {@code innerBlit} - and every
 * {@code private blitSprite(TextureAtlasSprite,...)} above it, including the nine-slice one - only does
 * {@code setShaderTexture}/{@code setShader} plus an immediate Tesselator draw. So a GUI sprite is
 * alpha-composited with whatever GL_BLEND state happens to be active.
 *
 * <p>GUI RenderTypes (items, text) toggle blending themselves when their batch is flushed and normally
 * leave it <em>off</em>, which is exactly how 1.20.1 Fabric ended up drawing the highlight border
 * unblended (pure black stroke, opaque antialiased corners). 1.21.2 does not have this problem because
 * there the blend mode is baked into the RenderPipeline.
 *
 * <p>Hence: turn blending on with the vanilla factors for our own draws and turn it off again, i.e. make
 * the state explicit instead of inheriting it - the same shape that fixed 1.20.1 ({@code 1.0.16}).
 */
public final class BlendGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("tooltipfix");
    private static final Set<String> REPORTED = new HashSet<>();

    private BlendGuard() {
    }

    /**
     * Enables blending with vanilla factors. The first time a given {@code site} draws, the inherited GL
     * state is logged, so the log shows what we were actually working with.
     */
    public static void begin(String site) {
        if (REPORTED.add(site)) {
            // Read-only GL query for forensics; never let a log line break the frame.
            try {
                LOGGER.info("[tooltipfix] {} inherited GL_BLEND={} src=0x{} dst=0x{} -> forcing enabled + default factors",
                        site,
                        GL11.glIsEnabled(GL11.GL_BLEND),
                        Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_SRC)),
                        Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_DST)));
            } catch (Throwable ignored) {
                // no GL context / query unavailable: the guard below is what actually matters
            }
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Restores the state the surrounding GUI code expects (the stock code paths all end with blending off). */
    public static void end() {
        RenderSystem.disableBlend();
    }
}
