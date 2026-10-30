package meow.tooltipfix.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The gui atlas is a private field on the draw helper; the sprite lookup needs it. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccess {
    @Accessor("guiSprites")
    TextureAtlas tooltipfix$guiSprites();
}
