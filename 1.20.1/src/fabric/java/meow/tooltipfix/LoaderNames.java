package meow.tooltipfix;

/**
 * 运行期字面名。Fabric 1.20.1 生产环境是全 intermediary 命名，所以这里写 intermediary 名，
 * 并且 mixin 用 remap = false 直接按字面名解析（不需要 refmap）。
 */
public final class LoaderNames {
    /** TooltipRenderUtil#renderTooltipBackground(GuiGraphics,int,int,int,int,int) 的 intermediary 名。 */
    public static final String RENDER_TOOLTIP_BACKGROUND = "method_47946";

    /** Gui#renderHotbar(float, GuiGraphics) —— 物品栏 / 选择框 / 副手 / 攻击指示器都在里面。 */
    public static final String GUI_RENDER_HOTBAR = "method_1759(FLnet/minecraft/class_332;)V";

    /** Gui#renderExperienceBar(GuiGraphics, int) —— 经验条。 */
    public static final String GUI_RENDER_EXPERIENCE_BAR = "method_1754(Lnet/minecraft/class_332;I)V";

    /** Gui#renderEffects(GuiGraphics) —— 右上角 HUD 药水效果框（背景 1.20.2 起改为 hud/effect_background sprite）。 */
    public static final String GUI_RENDER_EFFECTS = "method_1765(Lnet/minecraft/class_332;)V";

    /**
     * EffectRenderingInventoryScreen#renderBackgrounds(GuiGraphics, int, int, Iterable, boolean) ——
     * 物品栏右侧效果列表的背景（1.20.2 起改为 container/inventory/effect_background_large/_small sprite）。
     */
    public static final String EFFECT_SCREEN_RENDER_BACKGROUNDS =
            "method_18642(Lnet/minecraft/class_332;IILjava/lang/Iterable;Z)V";

    /** GuiGraphics#blit(ResourceLocation, int x, int y, int u, int v, int w, int h)：HUD 那几个固定 UV 的 blit。 */
    public static final String GUI_GRAPHICS_BLIT_7 =
            "Lnet/minecraft/class_332;method_25302(Lnet/minecraft/class_2960;IIIIII)V";

    /** AbstractButton#renderWidget(GuiGraphics, int, int, float) —— 按钮底板就在这里画。 */
    public static final String ABSTRACT_BUTTON_RENDER_WIDGET = "method_48579(Lnet/minecraft/class_332;IIF)V";

    /** GuiGraphics#blitNineSliced(ResourceLocation, int x10)：按钮底板用的 11 参重载。 */
    public static final String GUI_GRAPHICS_BLIT_NINE_SLICED_11 =
            "Lnet/minecraft/class_332;method_49697(Lnet/minecraft/class_2960;IIIIIIIIII)V";

    /**
     * AbstractContainerScreen#renderSlotHighlight(GuiGraphics, int, int, int) —— 格子悬浮高亮框的
     * 静态填充方法（1.21.2 起改为按 container/slot_highlight_{back,front} sprite 画）。
     *
     * <p>只给方法名：{@code @Inject(method = ...)} 的值只能是 {@code name} 或 {@code name(desc)ret}，
     * 不能带 owner（带 owner 的形式只用于 {@code @At(target = ...)}）。
     */
    public static final String RENDER_SLOT_HIGHLIGHT = "method_33285";

    /**
     * Forge / NeoForge 补丁版五参 {@code renderSlotHighlight(GuiGraphics,int,int,int,int)} 的字面名。
     *
     * <p>原版没有这个方法，是补丁加载器加的（用来传 slot 颜色），所以名字没进映射表。Fabric 上它不存在，
     * 对应 mixin 用 {@code require = 0} 静默跳过，这里保留常量只是为了两边共用的 common 源码能编译。
     */
    public static final String RENDER_SLOT_HIGHLIGHT_PATCHED =
            "renderSlotHighlight(Lnet/minecraft/client/gui/GuiGraphics;IIII)V";

    /**
     * AbstractContainerScreen#render 的「调用点重定向」目标方法名，仅为共用 common 源码而存在。
     *
     * <p>这里写的是 Forge 的 SRG 名：Fabric 运行期没有这个名字，对应的 {@code @Redirect}（{@code require = 0}）
     * 会静默跳过，不影响 Fabric 侧——Fabric 上没有会抢着 cancel 的 mod，两个 HEAD {@code @Inject} 照常生效。
     */
    public static final String RENDER = "m_88315_";

    private LoaderNames() {
    }
}
