package meow.tooltipfix.core;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * 1.12.2 的入口。
 *
 * <p>1.12.2 Forge 没有 Mixin 加载器，所以只能走 coremod：在 {@code Manifest} 里声明
 * {@code FMLCorePlugin}，FML 会在游戏主类加载前实例化我们，再用
 * {@link #getASMTransformerClass()} 拿到 {@link GuiTransformer} 挂进 LaunchClassLoader。
 * 同一个 jar 里有 {@code @Mod} 类，所以还要 {@code FMLCorePluginContainsFMLMod: true}。
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("TooltipFixCore")
@IFMLLoadingPlugin.TransformerExclusions({"meow.tooltipfix.core"})
public final class TooltipFixCore implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{GuiTransformer.class.getName(), GuiScreenTransformer.class.getName(),
                PanoramaTransformer.class.getName(), FontRendererTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
