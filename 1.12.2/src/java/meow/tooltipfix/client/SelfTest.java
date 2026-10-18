package meow.tooltipfix.client;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.init.MobEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

/**
 * 自动化回归测试：{@code -Dtooltipfix.selftest=true} 时，客户端在主菜单会自动进入一个复制出来的
 * 测试世界，依次打开「HUD / 物品栏 / 创造物品栏 / tooltip」四个画面并各截一张图，然后退出游戏。
 *
 * <p>为什么要在游戏内部截图而不是从外面点鼠标：GUI 的位置随 GUI scale 和窗口尺寸变化，外部点击
 * 坐标一改就全废；而这里直接调 {@code displayGuiScreen} + {@code ScreenShotHelper}，跑出来的图
 * 与分辨率、缩放无关，可反复对比。
 *
 * <p>每一步的节奏是「截上一屏 → 切到下一屏 → 等若干帧」，截图永远发生在目标画面已经至少渲染过
 * 一帧之后（{@code ScreenShotHelper} 读的是当前帧缓冲，同一 tick 里刚打开的界面还没画出来）。
 *
 * <p>测试世界由启动脚本从正式存档复制（{@code -Dtooltipfix.selftest.world=tooltipfix-test}），
 * 所以不会污染玩家的存档。
 */
public final class SelfTest {

    public static final boolean ON = Boolean.getBoolean("tooltipfix.selftest");

    private static final Logger LOGGER = LogManager.getLogger("tooltipfix");
    private static final String WORLD = System.getProperty("tooltipfix.selftest.world", "tooltipfix-test");

    /** 状态机步号；每一步都是「等 wait 帧 → 做一件事」。 */
    private static int stage;
    private static int wait;

    /** 主菜单已经渲染了多少帧（要等全景图渲出来再截图）。 */
    private static int menuTicks;
    /** 主菜单截图是否已拍。 */
    private static boolean menuShot;

    private SelfTest() {
    }

    public static void install() {
        if (!ON) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Ticker());
        LOGGER.info("[tooltipfix] selftest armed, world='{}'", WORLD);
    }

    private static final class Ticker {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            try {
                step(Minecraft.func_71410_x());
            } catch (Throwable t) {
                LOGGER.error("[tooltipfix] selftest failed at stage " + stage, t);
                stage = 99;
            }
        }
    }

    private static void step(Minecraft mc) {
        if (mc == null || stage >= 99) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        switch (stage) {
            case 0:
                if (!(mc.field_71462_r instanceof GuiMainMenu)) {
                    return;
                }
                // 主菜单本身就是回归项：全景图 + 标题 logo + 按钮贴图都在这一屏
                if (menuTicks++ < 40) {
                    return;
                }
                if (!menuShot) {
                    shoot(mc, "00-mainmenu");
                    probeMenuTextures(mc);
                    menuShot = true;
                    wait = 5;
                    return;
                }
                LOGGER.info("[tooltipfix] selftest: loading world '{}'", WORLD);
                mc.func_71371_a(WORLD, WORLD, null);
                stage = 1;
                wait = 20;
                return;
            case 1:
                if (mc.field_71441_e == null || mc.field_71439_g == null) {
                    return;
                }
                // 夜视：让 HUD 与物品栏都出现一条药水效果，覆盖 effect_background 的两种画法
                mc.field_71439_g.func_70690_d(new PotionEffect(MobEffects.field_76439_r, 1200, 0));
                // 生存模式才会开玩家物品栏（创造模式会把 GuiInventory 换成 GuiContainerCreative）
                gameMode(mc, "survival");
                LOGGER.info("[tooltipfix] selftest: world loaded");
                stage = 2;
                wait = 40;
                return;
            case 2:
                shoot(mc, "01-hud");
                // 攻击指示器只在「攻击冷却未满」时出现，而且必须在世界里的 HUD 帧上截，
                // 所以放在这里（后面几个界面都会把 HUD 盖住）。
                armCooldown(mc);
                armAttackCooldown(mc);
                stage = 20;
                wait = 3;
                return;
            case 20:
                shoot(mc, "07-cooldown");
                mc.func_147108_a(new AttackIndicatorPainter());
                stage = 21;
                wait = 10;
                return;
            case 21:
                shoot(mc, "08-attack-indicator");
                mc.func_147108_a(new GuiInventory(mc.field_71439_g));
                logScreen(mc, "opened GuiInventory");
                stage = 3;
                wait = 20;
                return;
            case 3:
                if (mc.field_71462_r == null) {
                    // 切游戏模式会让服务端重发容器，客户端会顺手关掉当前界面，这里补开一次
                    mc.func_147108_a(new GuiInventory(mc.field_71439_g));
                    wait = 10;
                    return;
                }
                shoot(mc, "02-inventory-mod");
                Debug.passthrough = true;
                stage = 4;
                wait = 5;
                return;
            case 4:
                if (mc.field_71462_r == null) {
                    mc.func_147108_a(new GuiInventory(mc.field_71439_g));
                    wait = 10;
                    return;
                }
                shoot(mc, "02-inventory-vanilla");
                Debug.passthrough = false;
                // 创造模式物品栏只有创造模式玩家才不会立刻自己关掉，用服务端命令切（不依赖作弊开关）
                gameMode(mc, "creative");
                stage = 5;
                wait = 30;
                return;
            case 5:
                mc.func_147108_a(new GuiContainerCreative(mc.field_71439_g));
                logScreen(mc, "opened GuiContainerCreative");
                stage = 6;
                wait = 20;
                return;
            case 6:
                shoot(mc, "03-creative-mod");
                Debug.passthrough = true;
                stage = 7;
                wait = 5;
                return;
            case 7:
                shoot(mc, "03-creative-vanilla");
                Debug.passthrough = false;
                // 把系统光标挪到创造物品栏正中：这样走的是「真实悬停」路径，
                // 同一张图里既有槽位高亮（SlotSprites），也有物品 tooltip（TooltipSprites）
                org.lwjgl.input.Mouse.setCursorPosition(mc.field_71443_c / 2, mc.field_71440_d / 2);
                LOGGER.info("[tooltipfix] selftest: cursor moved to {}x{}",
                        mc.field_71443_c / 2, mc.field_71440_d / 2);
                stage = 8;
                wait = 20;
                return;
            case 8:
                shoot(mc, "03b-creative-hover");
                mc.func_147108_a(new ItemTooltipPainter());
                stage = 9;
                wait = 10;
                return;
            case 9:
                shoot(mc, "06-itemtooltip-mod");
                Debug.passthrough = true;
                stage = 10;
                wait = 5;
                return;
            case 10:
                shoot(mc, "06-itemtooltip-vanilla");
                Debug.passthrough = false;
                mc.func_147108_a(new Painter());
                stage = 11;
                wait = 10;
                return;
            case 11:
                shoot(mc, "04-tooltip-mod");
                Debug.passthrough = true;
                stage = 12;
                wait = 5;
                return;
            case 12:
                shoot(mc, "04-tooltip-vanilla");
                Debug.passthrough = false;
                mc.func_147108_a(null);
                // 07-cooldown 已经在 stage 20 截过了（必须在世界 HUD 上截），这里直接收尾
                stage = 14;
                wait = 6;
                return;
            case 125:
                stage = 14;
                return;
            case 13:
                stage = 14;
                return;
            case 14:
                shoot(mc, "05-world");
                LOGGER.info("[tooltipfix] selftest: DONE");
                stage = 99;
                mc.func_71400_g();
                return;
            default:
                return;
        }
    }

    /**
     * 给主手槽塞一颗末影珍珠并上 100 tick 冷却。
     *
     * <p>1.12.2 的冷却覆盖层（{@code RenderItem.func_180453_a}）用 Tessellator 画 POSITION_COLOR quad，
     * **自己不 enableBlend**，靠调用方残留的混合状态；如果我们注入的 sprite 绘制把 blend 关掉，
     * 那条 127 alpha 的白色覆盖层就会变成不透明色块——即用户说的「不支持阿尔法通道」。
     */
    private static void armCooldown(Minecraft mc) {
        if (mc.field_71439_g == null) {
            return;
        }
        Item item = Item.func_111206_d("minecraft:ender_pearl");
        if (item == null) {
            return;
        }
        ItemStack stack = new ItemStack(item);
        mc.field_71439_g.field_71071_by.field_70461_c = 0;
        mc.field_71439_g.field_71071_by.field_70462_a.set(0, stack);
        mc.field_71439_g.func_184811_cZ().func_185145_a(stack.func_77973_b(), 100);
        LOGGER.info("[tooltipfix] selftest: cooldown armed on slot 0");
    }

    /**
     * 武装**攻击冷却**（准星下方那条 16x4 的进度条，{@code GuiIngame} 的 {@code (36,94)/(52,94)} 分支）。
     *
     * <p>它和上面那个物品冷却（{@code CooldownTracker}，画在快捷栏格子上）完全是两套东西：
     * 攻击冷却只看 {@code EntityPlayer.func_184825_o(f)}（已冷却攻击强度）是否 &lt; 1，
     * 强度由 {@code func_184821_cY()}（重置）后按 {@code func_184818_cX()}（冷却周期）慢慢回满。
     * 1.12.2 的手部冷却周期只有 5 tick，所以必须紧接着截图，否则早就回满、指示器不画。
     */
    private static void armAttackCooldown(Minecraft mc) {
        if (mc.field_71439_g == null) {
            return;
        }
        // 攻击指示器要设成「准星」模式（1），否则画的是快捷栏左边那个 18x18 版本
        mc.field_71474_y.field_186716_M = 1;
        mc.field_71439_g.func_184821_cY();
        LOGGER.info("[tooltipfix] selftest: attack cooldown armed strength="
                + mc.field_71439_g.func_184825_o(0.0F) + " period=" + mc.field_71439_g.func_184818_cX());
    }

    /**
     * 主菜单用到的贴图到底来自哪个材质包、多大：全景图 6 张 + 标题 logo + edition。
     * 1.12.2 的 GUI 只认 {@code textures/gui/title/**}，材质包若只在现代路径下放图就会静默回落原版。
     */
    private static void probeMenuTextures(Minecraft mc) {
        String[] names = {
                "textures/gui/title/background/panorama_0.png",
                "textures/gui/title/background/panorama_1.png",
                "textures/gui/title/background/panorama_2.png",
                "textures/gui/title/background/panorama_3.png",
                "textures/gui/title/background/panorama_4.png",
                "textures/gui/title/background/panorama_5.png",
                "textures/gui/title/minecraft.png",
                "textures/gui/title/edition.png",
        };
        net.minecraft.client.renderer.texture.TextureManager textures = mc.func_110434_K();
        for (String name : names) {
            try {
                net.minecraft.util.ResourceLocation location = new net.minecraft.util.ResourceLocation(name);
                java.util.List<net.minecraft.client.resources.IResource> stack =
                        mc.func_110442_L().func_135056_b(location);
                String pack = stack.isEmpty() ? "<none>" : stack.get(stack.size() - 1).func_177240_d();
                textures.func_110577_a(location);
                int width = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                int height = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
                LOGGER.info("[tooltipfix] selftest: texture {} -> {}x{} from '{}'", name, width, height, pack);
            } catch (Throwable t) {
                LOGGER.warn("[tooltipfix] selftest: texture probe failed for " + name, t);
            }
        }
    }

    /** 走服务端命令管理器切游戏模式，避免「单人游戏未开作弊」时 {@code /gamemode} 被拒。 */    private static void gameMode(Minecraft mc, String mode) {
        try {
            net.minecraft.server.MinecraftServer server = mc.func_71401_C();
            if (server == null) {
                LOGGER.warn("[tooltipfix] selftest: no integrated server, stay in current game mode");
                return;
            }
            String command = "gamemode " + mode + " " + mc.field_71439_g.func_70005_c_();
            server.func_71187_D().func_71556_a(server, command);
            LOGGER.info("[tooltipfix] selftest: ran server command '{}'", command);
        } catch (Throwable t) {
            LOGGER.error("[tooltipfix] selftest: failed to switch to " + mode, t);
        }
    }

    private static void logScreen(Minecraft mc, String what) {
        GuiScreen screen = mc.field_71462_r;
        LOGGER.info("[tooltipfix] selftest: {} -> current screen = {}", what,
                screen == null ? "null" : screen.getClass().getName());
    }

    private static void shoot(Minecraft mc, String name) {
        ScreenShotHelper.func_148259_a(mc.field_71412_D, "selftest-" + name + ".png",
                mc.field_71443_c, mc.field_71440_d, mc.func_147110_a());
        logScreen(mc, "screenshot " + name);
    }

    /** 单独的 tooltip 画面：直接用原版 {@code drawHoveringText} 画几种 tooltip（单行/多行/短行）。 */
    private static final class Painter extends GuiScreen {

        @Override
        public void func_73863_a(int mouseX, int mouseY, float partialTicks) {
            this.func_146276_q_();
            this.func_73732_a(this.field_146289_q, "tooltipfix selftest", this.field_146294_l / 2, 20, 0xFFFFFF);
            this.func_146283_a(Arrays.asList("夜視药水", "夜視 (8:00)"), this.field_146294_l / 2, 60);
            this.func_146283_a(Arrays.asList("Tooltip Fix", "second line", "third line"), 40, 140);
            this.func_146283_a(Arrays.asList("short"), 40, 220);
        }

        @Override
        public boolean func_73868_f() {
            return false;
        }
    }

    /**
     * 攻击指示器专项：按 1.12.2 {@code GuiIngame.func_175180_a} 的调用序列原样重放一遍。
     *
     * <p>为什么单开一屏：指示器只在「攻击冷却未满」时出现，冷却值来自服务端确认的挥击，
     * 自检里没法稳定复现；但渲染侧的条件是完全确定的——反色混合 + 三次 blit，重放即可。
     * 上半屏是原版画法（反色混合），下半屏故意用普通 alpha 混合作对照：材质包的贴图是
     * 「RGB 黑底当透明」，只有反色混合下纯黑才是 no-op，换成 alpha 混合就露成黑方块。
     */
    private static final class AttackIndicatorPainter extends GuiScreen {

        @Override
        public void func_73863_a(int mouseX, int mouseY, float partialTicks) {
            func_73734_a(0, 0, this.field_146294_l, this.field_146295_m, 0xFF7BA4E0);
            this.field_146289_q.func_78276_b("attack indicator: inverted blend (vanilla) / alpha blend (wrong)",
                    10, 10, 0xFFFFFF);
            Minecraft.func_71410_x().func_110434_K().func_110577_a(Gui.field_110324_m);

            this.field_146289_q.func_78276_b("inverted", 10, 30, 0xFFFFFF);
            // 原版 GuiIngame.func_175180_a 里紧跟着的是 func_179141_d()（那是 enableAlpha，
            // 不是 enableBlend！），混合其实靠 HUD 前面留下的「已开启」状态。这里要显式开 blend，
            // 否则 sprite 会被原样拷贝成黑底方块（上一版截图的假阳性就是这么来的）。
            GlStateManager.func_187428_a(SourceFactor.ONE_MINUS_DST_COLOR, DestFactor.ONE_MINUS_SRC_COLOR,
                    SourceFactor.ONE, DestFactor.ZERO);
            GlStateManager.func_179147_l();
            Hooks.blit(0.0F, 40, 44, 36, 94, 16, 4);
            Hooks.blit(0.0F, 40, 44, 52, 94, 8, 4);
            Hooks.blit(0.0F, 80, 38, 68, 94, 16, 16);

            this.field_146289_q.func_78276_b("alpha", 10, 80, 0xFFFFFF);
            GlStateManager.func_179147_l();
            GlStateManager.func_179112_b(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Hooks.blit(0.0F, 40, 94, 36, 94, 16, 4);
            Hooks.blit(0.0F, 40, 94, 52, 94, 8, 4);
            Hooks.blit(0.0F, 80, 88, 68, 94, 16, 16);
        }

        @Override
        public boolean func_73868_f() {
            return false;
        }
    }

    /**
     * 真实物品 tooltip 画面：直接调原版的 {@code GuiScreen.func_146285_a(ItemStack,int,int)}
     * —— 这正是 {@code GuiContainer} 悬停在物品格上时走的方法，能验证「从 ItemStack 生成行列表
     * → drawHoveringText」整条链路，而不只是 drawHoveringText 本身。
     */
    private static final class ItemTooltipPainter extends GuiScreen {

        @Override
        public void func_73863_a(int mouseX, int mouseY, float partialTicks) {
            this.func_146276_q_();
            this.func_73732_a(this.field_146289_q, "tooltipfix selftest / real ItemStack tooltips",
                    this.field_146294_l / 2, 10, 0xFFFFFF);
            this.func_146285_a(potion(), 40, 40);
            this.func_146285_a(named("minecraft:diamond_sword", "§bCozyUI 剑"), 200, 40);
            this.func_146285_a(named("minecraft:stone", "石头"), 40, 200);
        }

        @Override
        public boolean func_73868_f() {
            return false;
        }
    }

    /** 带夜视效果的药水（tooltip 多行：名字 + 效果 + 时长）。 */
    private static ItemStack potion() {
        ItemStack stack = new ItemStack(Item.func_111206_d("minecraft:potion"));
        NBTTagCompound tag = new NBTTagCompound();
        tag.func_74778_a("Potion", "minecraft:night_vision");
        stack.func_77982_d(tag);
        return stack;
    }

    /** 指定注册名 + 自定义显示名的物品。 */
    private static ItemStack named(String id, String name) {
        ItemStack stack = new ItemStack(Item.func_111206_d(id));
        stack.func_151001_c(name);
        return stack;
    }
}
