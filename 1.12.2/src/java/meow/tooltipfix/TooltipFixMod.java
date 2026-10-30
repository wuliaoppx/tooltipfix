package meow.tooltipfix;

import meow.tooltipfix.client.FontSmoothing;
import meow.tooltipfix.client.HdFont;
import meow.tooltipfix.client.SelfTest;
import meow.tooltipfix.client.SpriteReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 1.12.2 的 mod 入口，只做一件事：把 {@link SpriteReloadListener} 注册进资源管理器。
 *
 * <p>绘制逻辑全在 coremod 注入的 {@code Hooks} 里，不依赖任何 Forge 事件——因为原版调用
 * {@code Gui.drawTexturedModalRect} 的地方（HUD、按钮、槽位、tooltip）没有对应的可拦截事件。
 */
@Mod(
        modid = TooltipFixMod.MODID,
        name = "TooltipFix",
        version = "1.0.0",
        clientSideOnly = true,
        acceptableRemoteVersions = "*",
        acceptedMinecraftVersions = "[1.12,1.12.2]"
)
public final class TooltipFixMod {

    public static final String MODID = "tooltipfix";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft == null) {
            return;
        }
        IResourceManager manager = minecraft.func_110442_L();
        if (manager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) manager).func_110542_a(new SpriteReloadListener());
        }
        SelfTest.install();
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** 每 tick 调一次，让 {@link FontSmoothing} 有机会把字体贴图改成线性过滤。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FontSmoothing.tick();
            HdFont.tick();
        }
    }
}
