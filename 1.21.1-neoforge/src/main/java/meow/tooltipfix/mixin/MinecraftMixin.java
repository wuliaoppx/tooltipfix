package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.TooltipMode;
import meow.tooltipfix.client.TooltipSelftest;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每 tick 轮询配置（{@code Minecraft.runTick} 不受单人暂停影响，Gui.tick 在开屏时会跳过）。
 *
 * <p>ModernUI 在 {@code RenderTooltipEvent.Pre} 的 HIGH 优先级把框画掉、再在 LOW 优先级取消原版渲染，
 * 所以我们的 tooltip 注入永远不执行、也就永远没机会去翻它的 {@code sTooltip} 开关；必须在每 tick 时
 * 提前把两边状态摆正。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = LoaderNames.RUN_TICK, remap = false, at = @At("HEAD"))
    private void tooltipfix$pollMode(CallbackInfo ci) {
        TooltipMode.tick();
        TooltipSelftest.tick((Minecraft) (Object) this);
    }
}
