package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.TooltipMode;
import meow.tooltipfix.client.TooltipSelftest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Polls {@link TooltipMode} once per client tick.
 *
 * <p>The mode has to be resolved <em>before</em> a tooltip is drawn: when ModernUI is installed it
 * paints the box on {@code RenderTooltipEvent.Pre} at HIGH priority and then cancels the vanilla
 * render at LOW priority, so our tooltip injection never runs and could never flip its static
 * {@code sTooltip} switch. A per-tick poll keeps the two mods in the state the config file asks for.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = LoaderNames.GUI_TICK, remap = false, at = @At("HEAD"))
    private void tooltipfix$pollMode(CallbackInfo ci) {
        TooltipMode.tick();
        TooltipSelftest.tick(Minecraft.getInstance());
    }
}
