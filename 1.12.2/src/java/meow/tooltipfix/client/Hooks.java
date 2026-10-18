package meow.tooltipfix.client;

import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 被 coremod 注入到 {@code net.minecraft.client.gui.Gui} 里的三个入口。
 *
 * <p>注入方式见 {@code core/GuiTransformer}：整段方法体被替换成「读 zLevel → 调这里 → return」，
 * 所以这里的每个方法都必须「要么画 sprite 并返回 true，要么原样把 vanilla 的 quad 补回去」。
 * 补 quad 的代码在 {@link GuiRender}，是照 1.12.2 原版逐字复刻的。
 *
 * <p>为什么不用 Mixin：1.12.2 Forge 没有 Mixin 加载器，唯一干净的入口就是 IClassTransformer。
 * 为什么替换整个方法体而不是插桩调用点：被替换的四个方法都是直线代码（无跳转），换掉之后不需要
 * 重算 StackMapTable；而「在方法开头插指令」会让原有帧偏移整体漂移，一旦算错就是 VerifyError。
 */
public final class Hooks {

    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");
    private static final Set<String> REPORTED = new HashSet<>();

    private Hooks() {
    }

    /**
     * 注入点：{@code Gui.func_73729_b(int,int,int,int,int,int)}（= drawTexturedModalRect）。
     *
     * <p>1.12.2 的 HUD、按钮底板、槽位高亮后面的物件、药水效果背景全走这一个方法，所以它是主要战场。
     *
     * @param zLevel 调用方 {@code this.zLevel}，由注入的字节码 GETFIELD 后传进来（我们读不到 protected 字段）
     */
    public static void blit(float zLevel, int x, int y, int u, int v, int width, int height) {
        Debug.blit(zLevel, x, y, u, v, width, height);
        if (Debug.passthrough) {
            GuiRender.rawBlit(x, y, u, v, width, height, zLevel);
            return;
        }
        // 我们的 sprite 画完会把 GL_TEXTURE_2D 绑到自己的贴图上，而原版调用方普遍是
        // 「bindTexture 一次 → 连画好几张子图」（药水效果背景 + 图标就是典型），不会重绑。
        // 所以无论走哪条分支、成功还是抛异常，返回前都必须把绑定还原。
        int previousTexture = GuiRender.boundTexture();
        try {
            if (WidgetSprites.blit(zLevel, x, y, u, v, width, height)) {
                return;
            }
            if (HudSprites.blit(zLevel, x, y, u, v, width, height)) {
                return;
            }
            if (CreativeTabSprites.blit(zLevel, x, y, u, v, width, height)) {
                return;
            }
            if (EffectSprites.blit(zLevel, x, y, u, v, width, height)) {
                return;
            }
            if (LegacySheets.blit(zLevel, x, y, u, v, width, height)) {
                return;
            }
        } catch (Throwable t) {
            report("blit", t);
        } finally {
            GuiRender.rebind(previousTexture);
        }
        GuiRender.rawBlit(x, y, u, v, width, height, zLevel);
    }

    /**
     * 注入点：{@code Gui.func_73733_a(int,int,int,int,int,int)}（= drawGradientRect）。
     *
     * <p>槽位高亮就是一次 16x16 的白色渐变。tooltip 的渐变不在这里处理——整个
     * {@code drawHoveringText} 已经被 {@link #tooltip} 接管了。
     */
    public static void gradient(float zLevel, int left, int top, int right, int bottom,
                                int colorFrom, int colorTo) {
        Debug.gradient(zLevel, left, top, right, bottom, colorFrom, colorTo);
        if (Debug.passthrough) {
            GuiRender.rawGradient(left, top, right, bottom, colorFrom, colorTo, zLevel);
            return;
        }
        try {
            if (SlotSprites.gradient(zLevel, left, top, right, bottom, colorFrom, colorTo)) {
                return;
            }
        } catch (Throwable t) {
            report("gradient", t);
        }
        GuiRender.rawGradient(left, top, right, bottom, colorFrom, colorTo, zLevel);
    }

    /**
     * 注入点：{@code static Gui.func_73734_a(int,int,int,int,int)}（= Gui.drawRect）。
     *
     * <p>原版这个方法的 z 硬编码成 0，所以这里不需要 zLevel。
     */
    public static void rect(int left, int top, int right, int bottom, int color) {
        Debug.rect(left, top, right, bottom, color);
        if (Debug.passthrough) {
            GuiRender.rawRect(left, top, right, bottom, color);
            return;
        }
        try {
            if (SlotSprites.rect(left, top, right, bottom, color)) {
                return;
            }
        } catch (Throwable t) {
            report("rect", t);
        }
        GuiRender.rawRect(left, top, right, bottom, color);
    }

    /**
     * 注入点：{@code GuiScreen.func_146283_a(List,int,int)}（= drawHoveringText）。
     *
     * <p>这里不是「嗅探渐变」，而是把整个 tooltip 绘制接过来：1.12.2 的 tooltip 是 9 次
     * {@code drawGradientRect} 拼出来的，而那 9 次调用在实机里走的是未映射的
     * {@code invokevirtual GuiScreen.a(IIIIII)V}，根本到不了我们补丁过的 {@code Gui.func_73733_a}
     * （{@code TooltipSprites.gradient} 因此从未被触发过）。几何、GL 状态、文本绘制全部照原版复刻。
     *
     * @param lines 原版传进来的文本行，元素为 {@code String}（已带 § 颜色码）
     */
    public static void tooltip(net.minecraft.client.gui.GuiScreen screen,
                               java.util.List<String> lines, int mouseX, int mouseY) {
        tooltip(screen, lines, mouseX, mouseY, null);
    }

    /**
     * 注入点：Forge 给 {@code GuiScreen} 打的四参重载
     * {@code drawHoveringText(List,int,int,FontRenderer)}。
     *
     * <p><b>为什么必须连它一起换：</b>Forge 把 {@code renderToolTip(ItemStack,int,int)} 也改了，
     * 运行期它调的是四参版本（为了支持物品自定义字体），三参版本根本不在物品 tooltip 的路径上。
     * 只换三参时，只有极少数走三参的调用点会变成材质包样式——这就是「某些瞬间才生效」的原因。
     */
    public static void tooltip(net.minecraft.client.gui.GuiScreen screen,
                               java.util.List<String> lines, int mouseX, int mouseY,
                               net.minecraft.client.gui.FontRenderer font) {
        String screenName = screen == null ? "null" : screen.getClass().getName();
        int lineCount = lines == null ? -1 : lines.size();
        Debug.once("tooltip-call|" + screenName + "|" + lineCount,
                "[tooltipfix] tooltip call screen=" + screenName + " lines=" + lineCount
                        + (font == null ? "" : " font=" + font.getClass().getName()));
        try {
            TooltipSprites.draw(screen, lines, mouseX, mouseY, font);
        } catch (Throwable t) {
            report("tooltip", t);
            // 关键：这里绝对不能「什么都不画」。drawHoveringText 的整个方法体已经被换掉了，
            // 一旦这里抛异常又没有兜底，tooltip 会整体消失（用户报的「悬浮框都没了」就是这么来的）。
            try {
                TooltipSprites.drawFallback(screen, lines, mouseX, mouseY, font);
            } catch (Throwable ignored) {
                // 连原版样式都画不出来就只能放弃，但至少已经记了日志
            }
        }
    }

    private static void report(String site, Throwable t) {
        synchronized (REPORTED) {
            if (!REPORTED.add(site)) {
                return;
            }
        }
        LOGGER.error("[tooltipfix] " + site + " hook failed, falling back to vanilla drawing", t);
    }
}
