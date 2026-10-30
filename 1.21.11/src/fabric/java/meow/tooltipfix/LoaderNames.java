package meow.tooltipfix;

/**
 * Names that mixin annotations need as plain strings. Remapping never touches annotation
 * strings, so each loader variant carries the names its runtime actually uses.
 */
public final class LoaderNames {
    /** {@code SystemToast.render(GuiGraphics, Font, long)}. */
    public static final String TOAST_RENDER = "method_1986";
    /** {@code ToastManager.update()}. */
    public static final String TOAST_MANAGER_UPDATE = "method_61990";
    /** {@code GuiGraphics.guiSprites}. */
    public static final String GUI_SPRITES_FIELD = "field_61532";
    /** {@code GuiGraphics.blitSprite(RenderPipeline, Identifier, int, int, int, int)}. */
    public static final String BLIT_SPRITE_TARGET =
            "Lnet/minecraft/class_332;method_52706"
                    + "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/class_2960;IIII)V";

    private LoaderNames() {
    }
}
