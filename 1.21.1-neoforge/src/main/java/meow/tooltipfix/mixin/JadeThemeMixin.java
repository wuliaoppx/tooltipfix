package meow.tooltipfix.mixin;

import meow.tooltipfix.client.JadeBoxSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import snownee.jade.api.theme.Theme;

/**
 * Hooks the one place that hands out Jade's active {@code Theme} (1.21.1: Jade 15.10.x).
 *
 * <p>{@link JadeBoxSprites} needs the theme object itself, because the fix is to replace its
 * {@code tooltipStyle} with the sprite based one - Jade then renders the box with the same code
 * 1.21.11 uses. {@code IThemeHelper.get().theme()} is what every renderer asks for a theme, and
 * because it is Jade's own method (not a vanilla override) its name survives the
 * named -> intermediary remap of the Fabric jar, so {@code method = "theme"} needs no
 * {@code LoaderNames} entry and no descriptor on either loader.
 *
 * <p>{@link Pseudo} plus {@code require = 0}: without Jade the mixin is skipped, and if a future
 * Jade renames the method nothing is injected and the box simply keeps its colour style.
 */
@Pseudo
@Mixin(targets = "snownee.jade.impl.theme.ThemeHelper", remap = false)
public abstract class JadeThemeMixin {
    @Inject(method = "theme", remap = false, at = @At("RETURN"), require = 0)
    private void tooltipfix$useSpriteTooltipBox(CallbackInfoReturnable<Theme> cir) {
        JadeBoxSprites.apply(cir.getReturnValue());
    }
}
