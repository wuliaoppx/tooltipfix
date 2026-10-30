package meow.tooltipfix;

/**
 * Runtime names used inside mixin annotation strings.
 *
 * <p>NeoForge 1.21.1 runs Minecraft under the official (Mojang) names, so the strings here are the
 * literal source names. They are kept in one class because annotation strings are never rewritten
 * by the build script's remapper, unlike ordinary bytecode references.
 */
public final class LoaderNames {
    private LoaderNames() {
    }

    /** {@code SystemToast.renderBackgroundRow(GuiGraphics, int width, int vOff, int y, int h)} */
    public static final String TOAST_RENDER_BACKGROUND_ROW = "renderBackgroundRow";

    /** {@code SystemToast.render(GuiGraphics, ToastComponent, long)} */
    public static final String TOAST_RENDER = "render";

    /** {@code ToastComponent.render(GuiGraphics)} */
    public static final String TOAST_COMPONENT_RENDER = "render";

    /** {@code GuiGraphics.blitSprite(ResourceLocation, int, int, int, int)} */
    public static final String GUI_GRAPHICS_BLIT_SPRITE =
            "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V";

    /** {@code Gui.tick()} - runs every client tick, used to poll the config file. */
    public static final String GUI_TICK = "tick";
    public static final String RUN_TICK = "runTick";
}
