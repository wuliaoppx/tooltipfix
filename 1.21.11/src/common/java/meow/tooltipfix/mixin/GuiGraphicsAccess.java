package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code GuiGraphics.guiSprites} so the toast frame can look up its atlas sprite. */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccess {
    @Accessor(value = LoaderNames.GUI_SPRITES_FIELD, remap = false)
    TextureAtlas tooltipfix$guiSprites();
}
