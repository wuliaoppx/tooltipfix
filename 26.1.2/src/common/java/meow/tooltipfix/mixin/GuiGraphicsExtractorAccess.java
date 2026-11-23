package meow.tooltipfix.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The gui atlas is a private field on the draw helper; the sprite lookup needs it. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccess {
    @Accessor("guiSprites")
    TextureAtlas tooltipfix$guiSprites();

    @Invoker("innerBlit")
    void tooltipfix$innerBlit(RenderPipeline pipeline, Identifier texture,
                               int x0, int x1, int y0, int y1,
                               float u0, float u1, float v0, float v1, int color);
}
