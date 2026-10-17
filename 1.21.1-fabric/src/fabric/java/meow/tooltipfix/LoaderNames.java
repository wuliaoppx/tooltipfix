package meow.tooltipfix;

/**
 * Names of the vanilla members the mixins target, as they are called at runtime.
 *
 * <p>Fabric runs Minecraft in the intermediary namespace, so the mixins cannot put a Mojang name in
 * their annotations: the whole jar is compiled against official names and then remapped to
 * intermediary by the build script, but annotation <em>strings</em> are not touched by that remap.
 * Keeping them here - as the only place that knows about the runtime namespace - is why every
 * injection can use {@code remap = false} and why this mod needs no refmap.
 */
public final class LoaderNames {
    /** {@code TooltipRenderUtil.renderTooltipBackground(GuiGraphics, int, int, int, int, int)} */
    public static final String RENDER_TOOLTIP_BACKGROUND = "method_47946(Lnet/minecraft/class_332;IIIII)V";

    /** {@code GuiGraphics.blitNineSlicedSprite(TextureAtlasSprite, NineSlice, int, int, int, int, int)} */
    public static final String BLIT_NINE_SLICED_SPRITE = "method_52713";

    /** {@code GuiGraphics.innerBlit(ResourceLocation, int, int, int, int, int, float, float, float, float)} */
    public static final String INNER_BLIT = "method_25295";

    /**
     * {@code AbstractContainerScreen.renderSlotHighlight(GuiGraphics, int, int, int)} - the 1.21.2
     * {@code container/slot_highlight_back} / {@code container/slot_highlight_front} sprites replace
     * the flat fill this method paints.
     */
    public static final String RENDER_SLOT_HIGHLIGHT = "method_33285";

    private LoaderNames() {
    }
}
