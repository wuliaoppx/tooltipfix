package meow.tooltipfix;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * Ports the Minecraft 1.21.2 "resource packs can restyle the item tooltip box" feature back to
 * 1.21.1 NeoForge, as an independent patch mod (no upstream jar is modified).
 *
 * <p>1.21.2 replaced the hard-coded {@code fillGradient} tooltip background with two GUI sprites:
 * <ul>
 *   <li>{@code minecraft:tooltip/background} - the dark plate</li>
 *   <li>{@code minecraft:tooltip/frame} - the animated border</li>
 * </ul>
 * Both are nine-slice sprites, which is only possible because 1.21.2 also added the
 * {@code stretch_inner} nine-slice option. 1.21.1 only knows how to <em>tile</em> the inner region,
 * so without that option the tooltip (and every other nine-slice GUI sprite a modern pack ships)
 * renders with visible seams. This mod restores both halves:
 * <ol>
 *   <li>{@code GuiGraphics.blitNineSlicedSprite} honours {@code stretch_inner} again.</li>
 *   <li>{@code TooltipRenderUtil.renderTooltipBackground} draws the two sprites, falling back to
 *       the stock 1.21.1 colour rendering whenever the sprites are absent.</li>
 * </ol>
 */
@Mod(value = "tooltipfix", dist = Dist.CLIENT)
public class TooltipFix {
}
