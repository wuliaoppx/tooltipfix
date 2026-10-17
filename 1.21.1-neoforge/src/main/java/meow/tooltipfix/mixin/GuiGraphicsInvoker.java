package meow.tooltipfix.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code GuiGraphics#innerBlit} is package-private, and it is the only entry point that accepts raw
 * float atlas UVs for a sub-rectangle of a sprite. Needed by the stretched nine-slice renderer.
 */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {
    @Invoker("innerBlit")
    void tooltipfix$innerBlit(
            ResourceLocation atlasLocation,
            int x1, int x2, int y1, int y2, int z,
            float u0, float u1, float v0, float v1);
}
