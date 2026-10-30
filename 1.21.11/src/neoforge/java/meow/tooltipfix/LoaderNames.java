package meow.tooltipfix;

/**
 * Names that mixin annotations need as plain strings. Remapping never touches annotation
 * strings, so each loader variant carries the names its runtime actually uses.
 */
public final class LoaderNames {
    /** {@code SystemToast.render(GuiGraphics, Font, long)}. */
    public static final String TOAST_RENDER = "render";
    /** {@code ToastManager.update()}. */
    public static final String TOAST_MANAGER_UPDATE = "update";
    /** {@code GuiGraphics.guiSprites}. */
    public static final String GUI_SPRITES_FIELD = "guiSprites";
    /** {@code GuiGraphics.blitSprite(RenderPipeline, Identifier, int, int, int, int)}. */
    public static final String BLIT_SPRITE_TARGET =
            "Lnet/minecraft/client/gui/GuiGraphics;blitSprite"
                    + "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";

    private LoaderNames() {
    }
}
