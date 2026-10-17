package meow.tooltipfix.mixin;

import meow.tooltipfix.LoaderNames;
import meow.tooltipfix.client.SlotSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.2 的「材质包可自定义格子悬浮高亮框」移植。
 *
 * <p>1.20.1 里那层固定颜色的格子悬浮框是 static 方法
 * {@code AbstractContainerScreen.renderSlotHighlight(GuiGraphics, int, int, int)}（内部就一句
 * {@code fillGradient}），由 {@code render} 在画完槽位物品之后调用。这里在它 HEAD 处截住：材质包提供了
 * {@code container/slot_highlight_back} / {@code _front} 就按材质包画并取消原版填充，没提供就不取消。
 *
 * <p>目标方法是静态的，所以处理器必须是 {@code private static}，参数 = 原方法参数 + CallbackInfo。
 *
 * <p><b>为什么不再用 {@code @Redirect} 去抢 {@code render} 里那句调用</b>：PolyLib
 * （{@code polylib-forge.mixins.json:AbstractContainerScreenMixin#redirectHighlight}）重定向了同一句
 * invokestatic，而 Mixin 0.8.5 一个调用点只认一个 {@code @Redirect} —— 输的一方
 * {@code injectedCallbackCount == 0}、而 PolyLib 的 {@code require} 默认是 1，于是它 postInject 校验失败
 * 直接把游戏硬崩掉：
 * <pre>
 * &#64;Redirect conflict. Skipping polylib-forge.mixins.json:AbstractContainerScreenMixin-&gt;&#64;Redirect::redirectHighlight
 *   with priority 1000, already redirected by tooltipfix.mixins.json:...tooltipfix$slotHighlightCall with priority 2000
 * InjectionError: Critical injection failure: Redirector redirectHighlight(...) failed injection check, (0/1) succeeded.
 * </pre>
 * 用户机器上的 {@code 1.20.1-Forge_47.4.28} 实例就是这么崩的。改成本文件的两套 {@code @Inject} 之后，我们
 * 一个调用点都不占，PolyLib 的 {@code @Redirect} 爱怎么重定向怎么重定向 —— 它最终还是要落到
 * {@code m_280359_} / 五参方法上，而那两个方法的 HEAD 上挂着我们的钩子。
 *
 * <p><b>为什么要 priority = 500（比默认 1000 低）</b>：ImmersiveUI 的
 * {@code it.hurts.octostudios.immersiveui.mixin.AbstractContainerScreenMixin} 也往同一个方法的 HEAD 注入
 * （处理器 {@code disableSlotHighlight}），而且<b>无论配置都</b> {@code ci.cancel()}。同一个注入点上 Mixin 是
 * 「谁先应用谁的代码在前」，每个可取消回调后面还跟着 {@code if (ci.isCancelled()) return;}，所以先跑的那个能把
 * 后面的整个吞掉 —— 我们 1.0.22 抬到 2000 时日志里一行 slot highlight 都没有，正是被它吞了。
 * 而 mixin 的应用顺序是 priority <b>升序</b>（{@code MixinInfo.compareTo} 返回
 * {@code this.priority - other.priority}，TargetClassContext 用 TreeSet 保存 mixin），所以想排到前面必须把
 * priority 调<b>低</b>：500 < 1000 ⇒ 我们最先应用、回调排在最前，画完 cancel 之后 ImmersiveUI 那段 fill 也被跳过。
 *
 * <p>不要用「包裹 {@code render} 里那句调用」当兜底：{@code @Inject} 的处理器签名是<b>宿主方法</b>的参数 +
 * {@code CallbackInfo}（宿主 {@code render} 是 {@code (GuiGraphics,int,int,float)}），不是被调方法的参数，
 * 而且槽位坐标是循环里的局部变量，拿不到 —— 1.0.26/1.0.27 就是这么炸的
 * （{@code InvalidInjectionException: Invalid descriptor ... Expected (Lnet/minecraft/client/gui/GuiGraphics;IIFLorg/.../CallbackInfo;)V}）。
 *
 * <p>两个 HEAD hook 是不同加载器的差异，各自 {@code require = 0}，所以一端不匹配时静默跳过：
 * <ul>
 *   <li>Fabric / 原版：{@code render} 直接静态调用四参 {@code renderSlotHighlight(GuiGraphics,int,int,int)}
 *       （intermediary {@code method_33285}）。</li>
 *   <li>Forge：Forge 补丁把 {@code render} 的调用改成了自己新增的五参
 *       {@code renderSlotHighlight(GuiGraphics,int,int,int,int)}（plain name，没被 SRG 重命名），
 *       四参 {@code m_280359_} 反而只是它的包装 —— javap forge-1.20.1-47.4.26-client.jar 确认
 *       {@code m_88315_} 在字节码 192 处 {@code invokestatic renderSlotHighlight:(…;IIII)V}。</li>
 * </ul>
 */
@Mixin(value = AbstractContainerScreen.class, priority = 500)
public abstract class AbstractContainerScreenMixin {
    @Inject(
            method = LoaderNames.RENDER_SLOT_HIGHLIGHT,
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void tooltipfix$slotHighlight(GuiGraphics graphics, int x, int y, int blitOffset,
                                                CallbackInfo ci) {
        SlotSprites.noteHookFired();
        if (SlotSprites.draw(graphics, x, y)) {
            ci.cancel();
        }
    }

    @Inject(
            method = LoaderNames.RENDER_SLOT_HIGHLIGHT_PATCHED,
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void tooltipfix$slotHighlightPatched(GuiGraphics graphics, int x, int y, int blitOffset,
                                                       int color, CallbackInfo ci) {
        SlotSprites.noteHookFired();
        if (SlotSprites.draw(graphics, x, y)) {
            ci.cancel();
        }
    }
}
