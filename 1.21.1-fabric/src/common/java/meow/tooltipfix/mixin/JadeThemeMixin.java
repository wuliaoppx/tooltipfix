package meow.tooltipfix.mixin;

import meow.tooltipfix.client.JadeBoxSprites;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import snownee.jade.api.JadeIds;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.impl.ui.BoxElement;
import snownee.jade.impl.ui.StyledElement;

/** Swap just the root box's background draw, not the style used by Jade's layout and progress bar. */
@Pseudo
@Mixin(targets = "snownee.jade.impl.ui.BoxElement", remap = false)
public abstract class JadeThemeMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lsnownee/jade/api/ui/BoxStyle;render"), require = 0)
    private void tooltipfix$drawRootBackground(BoxStyle style, GuiGraphics graphics,
            StyledElement element, float x, float y, float w, float h, float alpha) {
        if (!((Object) this instanceof BoxElement box) || !JadeIds.ROOT.equals(box.getTag())
                || !JadeBoxSprites.draw(style, graphics, x, y, w, h, alpha)) {
            style.render(graphics, element, x, y, w, h, alpha);
        }
    }
}
