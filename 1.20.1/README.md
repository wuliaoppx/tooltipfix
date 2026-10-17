# TooltipFix — 1.20.1（Forge 47.x / Fabric）

把 **1.21.2 起的「材质包可重定义 GUI 外观」**回移到 1.20.1，一套 common 源码编出两个 jar，不改任何上游 jar。

| 加载器 | 产物 | 运行期命名空间 |
| --- | --- | --- |
| Forge 47.4.26+ | `build\tooltipfix-1.20.1-forge-1.0.28.jar` | 官方类名 + **SRG 成员名**（`m_280205_`） |
| Fabric（纯 mixin mod，无需 Fabric API） | `build\tooltipfix-1.20.1-fabric-1.0.28.jar` | 全 **intermediary**（`class_332` / `method_47946`） |

## 为什么 1.20.1 比 1.21.1 麻烦得多

1. **1.20.1 根本没有 GUI sprite / nine_slice 体系**——`GuiSpriteManager`、`GuiSpriteScaling`、`NineSlice`
   都是 **1.20.2** 才引入的。没有现成管线可注入，所以 `GuiSprite.java` 自己读 PNG + `.mcmeta`，
   自己实现九宫格、`stretch_inner`、逻辑画布与动画帧。
2. 回移到 1.20.1 的每一块都要自己找「原版那一次具体绘制调用」去拦：`GuiGraphics.blit(...)`、
   `AbstractButton.renderWidget`、`EffectRenderingInventoryScreen` 的效果背景、`Gui` 的 HUD 全套。
3. `GuiGraphics` 有一个 **public 的 11 参 `blit`**（源尺寸与目标尺寸分开），
   正好是「拉伸九宫格」需要的，所以不需要 accessor mixin，也不用自己写顶点缓冲。
4. 两个加载器都要求**整包重映射**。本项目的做法是绕开 refmap：mixin 用 `remap = false` +
   各加载器运行期的**字面名**（`LoaderNames`），整个 jar 由构建脚本统一重映射。

## 目录

```
src/common/java/meow/tooltipfix/client/GuiSprite.java     自己实现的 1.20.2 sprite 管线
                                                           （读 mcmeta、nine_slice、stretch_inner、animation、逻辑画布）
src/common/java/meow/tooltipfix/client/TooltipSprites.java 物品悬浮框 sprite
src/common/java/meow/tooltipfix/client/SlotSprites.java    格子悬浮高亮框 sprite
src/common/java/meow/tooltipfix/client/HudSprites.java     HUD（快捷栏 / 选中框 / 副手 / 经验条 / 跳跃条 / 攻击指示器）
src/common/java/meow/tooltipfix/client/WidgetSprites.java  按钮底板
src/common/java/meow/tooltipfix/client/EffectSprites.java  药水效果背景
src/common/java/meow/tooltipfix/client/BlendState.java     查询真实 GL_BLEND 开关（只读）
src/common/java/meow/tooltipfix/mixin/                     6 个注入点（见下）
src/common/resources/                                      默认贴图 + mixin 配置
src/forge/                                                 LoaderNames(SRG) + @Mod + mods.toml
src/fabric/                                                LoaderNames(intermediary) + fabric.mod.json
tools/Remap.java                                           自写 ASM 重映射器
tools/build-maps.js + hierarchy.js + to-tiny.js            从 Mojang 映射表生成 tiny v2
```

注入点与它们替代的原版绘制：

| mixin | 原版绘制 | 替换为 |
| --- | --- | --- |
| `TooltipRenderUtilMixin` | `renderTooltipBackground` 的 `fillGradient` | `tooltip/background` + `tooltip/frame` |
| `AbstractContainerScreenMixin` | `renderSlotHighlight`（四参 `m_280359_`，Forge 另有五参重载） | `container/slot_highlight_back` / `_front` |
| `AbstractButtonMixin` | `blit(WIDGETS, …)` 四态按钮底板 | `widget/button*` |
| `GuiHudMixin` | HUD 的 `blit`（快捷栏 / 选中框 / 副手 / 经验条 / 跳跃条 / 攻击指示器） | 对应 `hud/*` sprite |
| `EffectRenderingInventoryScreenMixin` + `GuiHudMixin` | 药水效果背景（HUD 图标底 + 背包效果列表底） | `hud/effect_background(_ambient)`、`container/inventory/effect_background_large` / `_small` |
| `forge/TooltipRenderUtilColorMixin` | Forge 侧 tooltip 颜色参数 | 同上（颜色被 sprite 取代） |

## 踩过的坑（都写在代码注释里）

- **GL 混合状态**：1.20.1 的 `GuiGraphics` 不替你管 `GL_BLEND`，无颜色的 `innerBlit` 直接按调用点遗留状态合成
  → 自绘前后 `enableBlend() + defaultBlendFunc()` … `disableBlend()`，并用 `BlendState` 还原原状态。
- **不要全局接管 GUI sprite 的过滤**：给所有 sprite 统一降采样/线性过滤会让按钮、选中框、高亮框一起发虚。
  只有个别**超采样**贴图（例如 4 倍尺寸的 `slot_highlight_*`）才按需点名设 `GL_LINEAR`。
- **别抢别人的调用点**：同一个 `renderSlotHighlight` 调用点若第三方模组也 `@Redirect`，Mixin 只允许一个赢家，
  输家 `require = 1` 会直接 `InjectionError` 崩启动。所以高亮框最终改成在方法 **HEAD** 注入，不占调用点。
- **别给 mixin 抬 priority 求「后执行」**：Mixin 的应用顺序是 priority **升序**，要排到别人前面得调低。

## 构建

```powershell
set JAVA_HOME=C:\Program Files\Java\jdk-17
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -McRoot "%APPDATA%\.minecraft"
```

构建脚本做的事：`javac --release 17` 对着**具名 1.20.1 jar** 编译 → `tools/Remap.java` 把整个 jar
重映射到目标命名空间（Forge: named→SRG，Fabric: named→intermediary）→ 拼装资源与元数据 → 出两个 jar。

### 构建前需要自备（本仓库不含）

| 需要 | 位置 | 说明 |
| --- | --- | --- |
| 具名 1.20.1 类 | `1.20.1-named.jar` | ForgeGradle / NeoGradle 的 named 输出，或 Vineflower 反编译的 client+server |
| 映射表 | `tools\maps\named-srg.tiny`、`tools\maps\named-intermediary.tiny` | 用 `node tools\build-maps.js` + `to-tiny.js` 从 Mojang 映射表生成 |
| ASM 9.7 jar 路径 | `tools\asm-cp.txt` | 复制 `tools\asm-cp.txt.example`，一行一个路径 |
| 加载器库 | `-McRoot` 下的 `libraries\` | 任意装过 Forge 47.4.26 / Fabric 的实例 |

`tools/Remap.java` 会在首次构建时自动编译到 `tools\remap-build\`。

`-McRoot` 默认 `%APPDATA%\.minecraft`，实例装在别处时显式传参。

## 安装

```
<实例>\mods\tooltipfix-1.20.1-<forge|fabric>-1.0.28.jar
<实例>\resourcepacks\<面向 1.21.2+ 的材质包>.zip
```

材质包没提供的 sprite 一律回落原版绘制，所以不装材质包时行为与 1.20.1 原版一致。

## 已知取舍

- 动画自己按 `animation.frametime` / `frames` 算 V 偏移，**不实现 `interpolate`** 帧间插值。
- 只回移材质包真能改的那几块：滑条 / 输入框 / 复选框 / 滚动条 / 标签页仍走 1.20.1 老的 `widgets.png`。
- tooltip 走 sprite 后原版那套颜色常量不再起作用（与 1.21.2 一致）。
- 同一个 jar 里两个加载器的 `LoaderNames` 是编译期分开的，改运行期字面名时**两边都要改**。
