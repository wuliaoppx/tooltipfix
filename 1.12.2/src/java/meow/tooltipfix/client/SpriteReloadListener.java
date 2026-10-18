package meow.tooltipfix.client;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;

/**
 * 资源包重载（F3+T 换包）后必须丢掉所有 sprite 缓存。
 *
 * <p>我们在加载 sprite 时自己把 PNG 上传成了 GL 贴图（不经过原版的 TextureManager），
 * 所以原版重载纹理不会帮我们回收；旧贴图不删就是显存泄漏，缓存不清就会一直画已经失效的 gl id。
 */
public final class SpriteReloadListener implements IResourceManagerReloadListener {

    @Override
    public void func_110549_a(IResourceManager manager) {
        // 顺序：先丢引用（其它类的 reset 只清自己的字段），最后 invalidateCache 统一删 GL 贴图。
        TooltipSprites.reset();
        SlotSprites.reset();
        WidgetSprites.reset();
        HudSprites.reset();
        GuiSprite.invalidateCache();
        // 重载后 GL 贴图 id 会被回收复用，id→资源名的缓存必须一起清（否则会把旧包当新包画）
        Debug.resetTextureCache();
        FontSmoothing.reset();
        TitleFilter.reset();
        HdFont.reload();
    }
}
