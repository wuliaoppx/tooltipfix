# TooltipFix — 1.21.1 NeoForge

把 Minecraft **1.21.2** 的「材质包可以自定义 GUI 外观」以**独立补丁模组**的形式回移到 **1.21.1 NeoForge**。
不改任何上游模组/原版 jar。

## 1.21.2 到底改了什么

1.21.2 把 tooltip 外框从硬编码的 `fillGradient` 换成了两张 GUI sprite：

| sprite id | 文件 | 作用 |
| --- | --- | --- |
| `minecraft:tooltip/background` | `assets/minecraft/textures/gui/sprites/tooltip/background.png` | 深色底板 |
| `minecraft:tooltip/frame` | `assets/minecraft/textures/gui/sprites/tooltip/frame.png` | 边框（可动图） |

两者都是 `nine_slice`。而 1.21.2 **同时**给 nine_slice 加了 `"stretch_inner": true` 选项
（内区拉伸而不是平铺）——没有这个选项，tooltip 边框以及 1.21.2 材质包里所有用到该选项的
GUI sprite（按钮 / 滑条 / 输入框 / toast / 收纳袋进度条…）在 1.21.1 上都会平铺出接缝。

所以本模组补三件事：

1. **`stretch_inner` 全局回归**：`GuiGraphics.blitNineSlicedSprite` 上注入，命中该选项时改用
   拉伸九宫格（自绘九块 + `innerBlit`）。
2. **tooltip 改用 sprite 绘制**：`TooltipRenderUtil.renderTooltipBackground` 两个重载都注入，
   按 1.21.2 的偏移（3px 文本留白 + 9px sprite 外扩 = 四周各 12px）贴出 background + frame。
   1.21.2 的颜色语义被 sprite 取代，所以 `RenderTooltipEvent.Color` 的四个颜色不再生效——与原版行为一致。
3. **格子悬浮高亮框**：`renderSlotHighlight` 改画 `container/slot_highlight_back` / `_front`。

若 sprite 不存在（缺资源），注入不 cancel，自动回落到 1.21.1 原来的颜色绘制。

## 目录结构

```
src/main/java/meow/tooltipfix/
  TooltipFix.java                         @Mod(dist=CLIENT)
  client/TooltipSprites.java              1.21.2 的 tooltip 绘制
  client/SlotSprites.java                 格子悬浮高亮框
  client/GuiSpriteStretching.java         自绘拉伸九宫格 + 读 mcmeta 的 stretch_inner
  client/BlendGuard.java                  自管 GL_BLEND 状态
  mixin/TooltipRenderUtilMixin.java       tooltip 注入
  mixin/AbstractContainerScreenMixin.java slot_highlight 注入
  mixin/GuiGraphicsMixin.java             blitNineSlicedSprite 注入
  mixin/GuiGraphicsInvoker.java           @Invoker innerBlit
src/main/resources/
  META-INF/neoforge.mods.toml
  tooltipfix.mixins.json
  pack.mcmeta
```

**贴图由材质包提供，模组不自带。** `TooltipSprites` 会先确认 `tooltip/background` 和 `tooltip/frame`
是否真的在 GUI atlas 上（`TextureAtlas#getSprite` 对未知 id 会返回 missingno，据此判定），
没提供就不 cancel，回落到 1.21.1 原来的颜色绘制。

> 为什么不打包默认贴图：在 Fabric 上模组资源包排在用户材质包**之前**，自带贴图会把用户的包永久盖掉。
> 这条经验来自 1.20.1 那版的教训，两个 1.21.1 变体都不自带。

## 构建

没有 Gradle：直接对着本机已装的 NeoForge 1.21.1 运行时用 javac 编译。
NeoForge 1.20.2+ 生产环境跑的就是 Mojang official 映射，所以 mixin **不需要 refmap**。

```powershell
set JAVA_HOME=C:\Program Files\<jdk 21>
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
# -> build/tooltipfix-1.0.6.jar
```

构建脚本用到的两个本机路径，换机器时用参数覆盖：

* `-NamedMcClasses <dir>`：具名（mojmap）MC 类目录，默认自动从
  `%USERPROFILE%\.gradle\caches\ng_execute\*\output` 里找含 `net/minecraft/client/gui/GuiGraphics.class` 的那个。
* `-McRoot`：`.minecraft` 根目录，默认 `%APPDATA%\.minecraft`。

## 已知取舍

* 材质包提供了 `tooltip/*` 时 tooltip 走 sprite 绘制，NeoForge 的 `RenderTooltipEvent.Color`
  不再能改 tooltip 底色 —— 1.21.2 本身也是如此；没提供时仍是 1.21.1 原版颜色框。
* `tooltip_style` 物品组件（1.21.2 允许单个物品指定 `tooltip/<style>_background`）没有移植，
  属于数据组件而非材质包功能。`GuiSpriteStretching`/`TooltipSprites` 的结构可以直接扩展。
* `interpolate`（帧间插值）不生效，只按 `frametime` 逐帧切换。
