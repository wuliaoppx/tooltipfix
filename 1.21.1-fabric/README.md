# tooltipfix — 1.21.1 Fabric

把 1.21.2 的「材质包可自定义 GUI」回移到 **1.21.1 Fabric**，做成独立补丁模组（不改任何上游 jar）。

## 它做什么

1.21.1 的悬浮框是 `TooltipRenderUtil` 用四个颜色常量 `fill` / `fillGradient` 画出来的，材质包改不了。
1.21.2 改成 blit 两张九宫格 GUI sprite：

```
assets/minecraft/textures/gui/sprites/tooltip/background.png  ← 底板
assets/minecraft/textures/gui/sprites/tooltip/frame.png       ← 动态边框
```

配套新增了 `gui.scaling` 里的 `"stretch_inner": true`（内区拉伸而不是平铺）。1.21.1 会静默丢弃这个未知字段，
所以照 1.21.2 写的包在 1.21.1 上会平铺错位。

本模组做三件事：

| 钩子 | 作用 |
| --- | --- |
| `TooltipRenderUtil.renderTooltipBackground`（`@Inject` HEAD, cancel） | 悬浮框改画 `tooltip/background` + `tooltip/frame` 两张 sprite，几何与 1.21.2 一致（文本 3px 内边距 + sprite 外扩 9px） |
| `GuiGraphics.blitNineSlicedSprite`（`@Inject` HEAD, cancel） | 给整条 GUI sprite 管线补上 `stretch_inner`：只对声明了该字段的 sprite 生效，其余走原版 |
| `AbstractContainerScreen.renderSlotHighlight`（`@Inject` HEAD, cancel） | 格子悬浮高亮框改画 `container/slot_highlight_back` / `_front` |

**贴图由材质包提供，模组不自带。** 原因见下。

## 为什么不自带默认贴图

第一版把 1.21.2 原版的两张 tooltip 贴图塞进了模组 jar。结果在 Fabric 上模组资源包**排在用户材质包前面**，
用户的包永远被盖掉，悬浮框永远是模组自带的样子——1.20.1 那版就是被这个坑到的，所以这里干脆不带贴图：

- 材质包提供了 sprite → 画材质包的样子（想要的效果）；
- 没提供 → `MissingTextureAtlasSprite` 检测命中，**不 cancel**，回落原版颜色框。

1.21.1 原版 `assets/minecraft/atlases/gui.json` 收录的是整个 `gui/sprites` 目录，所以包里的
`tooltip/*.png`、`container/slot_highlight_*.png` 会被自动 stitch 进 GUI atlas，模组不需要额外提供 atlas 定义。

## 构建

不需要 Gradle：

```powershell
set JAVA_HOME=C:\Program Files\<jdk 21>
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -NamedClasses <具名 1.21.1 类目录>
```

产物：`build\tooltipfix-1.21.1-fabric-1.0.4.jar`

流程：

1. `javac --release 21` 对着**官方名（named）的 1.21.1 类**编译（省略 `-NamedClasses` 时会去
   `%USERPROFILE%\.gradle\caches\ng_execute\*\output` 里自动找）；
2. `tools\Remap.java`（自写 ASM 重映射器）把整个 jar 从 named 重映射到 **intermediary**；
3. 打上 `fabric.mod.json` / `tooltipfix.mixins.json` / `pack.mcmeta`。

构建前自备（本仓库不含）：具名 1.21.1 类、`tools\maps\named-intermediary.tiny`（用 `tools\build-maps.js` +
`to-tiny.js` 从 Mojang 映射表生成）、`tools\asm-cp.txt`（复制 `tools\asm-cp.txt.example` 填 ASM 9.7 jar 路径）、
`-McRoot` 下的 `libraries\`（默认 `%APPDATA%\.minecraft`）。

### 为什么 mixin 字符串要写在 LoaderNames 里

整包重映射只改**字节码里的类/成员引用**，不改注解里的字符串。Fabric 运行在 intermediary 命名空间，所以：

- `@Mixin(TooltipRenderUtil.class)` 里的类引用会被重映射器自动改成 `class_8002`；
- 但 `@Inject(method = "renderTooltipBackground(...)")` 的字符串不会，于是全部走
  `remap = false` + `LoaderNames`（intermediary 字面量）：

| LoaderNames | intermediary |
| --- | --- |
| `RENDER_TOOLTIP_BACKGROUND` | `method_47946(Lnet/minecraft/class_332;IIIII)V` |
| `BLIT_NINE_SLICED_SPRITE` | `method_52713` |
| `INNER_BLIT` | `method_25295` |

好处是**不需要 refmap**：注解里没有官方名，就没有需要重映射的东西。
`innerBlit` 是 package-private，只能用 `@Invoker`（invoker 的 `remap = false` 同样写在 LoaderNames 上）。

## 安装

```
<实例>\mods\tooltipfix-1.21.1-fabric-1.0.4.jar
<实例>\resourcepacks\<面向 1.21.2+ 的材质包>.zip      ← 提供 tooltip/* 与 container/slot_highlight_*
```

依赖：Fabric Loader ≥ 0.15、Minecraft 1.21.1、Java 21。不需要 Fabric API。

## 已知取舍

- 1.21.2 的 `tooltip_style` 物品组件（`tooltip/<style>_background`）没有移植。
- frame 的 `interpolate`（帧间插值）不生效，只按 `frametime` 逐帧切换。
- 材质包若只提供 `background` 或只提供 `frame` 其中一张，视为没提供，整框回落原版颜色。
