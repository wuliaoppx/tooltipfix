package meow.tooltipfix;

/**
 * 运行期字面名。1.20.1 的 Forge 生产环境是「官方类名 + SRG 成员名」，所以这里写 SRG 名，
 * 并且 mixin 用 remap = false 直接按字面名解析（不需要 refmap）。
 */
public final class LoaderNames {
    /** TooltipRenderUtil#renderTooltipBackground(GuiGraphics,int,int,int,int,int) 的 SRG 名。 */
    public static final String RENDER_TOOLTIP_BACKGROUND = "m_280205_";

    /**
     * Forge 补丁版十参 renderTooltipBackground 的运行期字面名。
     *
     * <p>这个方法不是原版的，是 Forge 为 RenderTooltipEvent.Color 加的，所以它的名字没有被
     * SRG 重命名，运行期就叫 renderTooltipBackground（可从 forge-...-client.jar javap 验证）。
     * GuiGraphics.renderTooltipInternal 调用的正是它。
     */
    public static final String RENDER_TOOLTIP_BACKGROUND_COLORED =
            "renderTooltipBackground(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIII)V";

    /** Gui#renderHotbar(float, GuiGraphics) 的 SRG 名。 */
    public static final String GUI_RENDER_HOTBAR = "m_280518_(FLnet/minecraft/client/gui/GuiGraphics;)V";

    /** Gui#renderExperienceBar(GuiGraphics, int) 的 SRG 名。 */
    public static final String GUI_RENDER_EXPERIENCE_BAR = "m_280276_(Lnet/minecraft/client/gui/GuiGraphics;I)V";

    /** Gui#renderEffects(GuiGraphics) 的 SRG 名 —— 右上角 HUD 药水效果框。 */
    public static final String GUI_RENDER_EFFECTS = "m_280523_(Lnet/minecraft/client/gui/GuiGraphics;)V";

    /**
     * EffectRenderingInventoryScreen#renderBackgrounds(GuiGraphics, int, int, Iterable, boolean) 的 SRG 名 ——
     * 物品栏右侧效果列表的背景（1.20.2 起改为 container/inventory/effect_background_large/_small sprite）。
     */
    public static final String EFFECT_SCREEN_RENDER_BACKGROUNDS =
            "m_280172_(Lnet/minecraft/client/gui/GuiGraphics;IILjava/lang/Iterable;Z)V";

    /** GuiGraphics#blit(ResourceLocation, int, int, int, int, int, int) 的 SRG 名（带 owner 的 @At target 形式）。 */
    public static final String GUI_GRAPHICS_BLIT_7 =
            "Lnet/minecraft/client/gui/GuiGraphics;m_280218_(Lnet/minecraft/resources/ResourceLocation;IIIIII)V";

    /** AbstractButton#renderWidget(GuiGraphics, int, int, float) 的 SRG 名。 */
    public static final String ABSTRACT_BUTTON_RENDER_WIDGET = "m_87963_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V";

    /** GuiGraphics#blitNineSliced(ResourceLocation, int x10) 的 SRG 名。 */
    public static final String GUI_GRAPHICS_BLIT_NINE_SLICED_11 =
            "Lnet/minecraft/client/gui/GuiGraphics;m_280027_(Lnet/minecraft/resources/ResourceLocation;IIIIIIIIII)V";

    /**
     * AbstractContainerScreen#renderSlotHighlight(GuiGraphics, int, int, int) 的 SRG 名 —— 格子悬浮
     * 高亮框的静态填充方法（1.21.2 起改为按 container/slot_highlight_{back,front} sprite 画）。
     *
     * <p>只给方法名：{@code @Inject(method = ...)} 的值只能是 {@code name} 或 {@code name(desc)ret}，
     * 不能带 owner（带 owner 的形式只用于 {@code @At(target = ...)}）。
     */
    public static final String RENDER_SLOT_HIGHLIGHT = "m_280359_";

    /**
     * Forge 补丁版五参 {@code renderSlotHighlight(GuiGraphics,int,int,int,int)} 的运行期字面名。
     *
     * <p>1.20.1 Forge 的 {@code m_88315_}（render）在字节码 192 处 {@code invokestatic} 的就是它
     * （javap forge-...-client.jar 验证），四参 {@code m_280359_} 只是它的一层包装；方法的真正填充体
     * （{@code RenderType.m_286086_()} + {@code GuiGraphics.m_285978_(…)}）也在这个五参方法里。
     * 不是原版方法，所以没被 SRG 重命名。
     */
    public static final String RENDER_SLOT_HIGHLIGHT_PATCHED =
            "renderSlotHighlight(Lnet/minecraft/client/gui/GuiGraphics;IIII)V";

    /**
     * AbstractContainerScreen#render(GuiGraphics, int, int, float) 的 SRG 名，只给「调用点重定向」用。
     *
     * <p>为什么要在调用点上动手：ImmersiveUI 也在 {@code renderSlotHighlight} 的 HEAD 注入，而且
     * <b>无论配置都 {@code ci.cancel()}</b>（配置开时先自己画一遍原版白色 fill）。Mixin 在同一个注入点上
     * 是「谁先应用谁的代码在前」，每个可取消回调后面还跟着一句 {@code if (ci.isCancelled()) return;}，
     * 所以只要它排在我们前面（实测就是如此，priority 抬到 2000 也没用），我们的 HEAD 回调根本不会执行。
     * 把 {@code m_88315_} 里那次 {@code invokestatic renderSlotHighlight(…;IIII)V} 直接重定向到我们的处理器，
     * 被调用的方法压根不会运行，也就不存在谁先把谁吞掉的问题。
     */
    public static final String RENDER = "m_88315_";

    private LoaderNames() {
    }
}
