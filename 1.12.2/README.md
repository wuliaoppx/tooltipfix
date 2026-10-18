# tooltipfix — 1.12.2 分支

把 **1.21.2 时代材质包**（主要验证对象：[CozyUI](https://modrinth.com/resourcepack/cozyui) v1.10）里
的 GUI / HUD / 字体资源，正确映射到 **Minecraft 1.12.2 Forge** 的渲染管线上。

> 一句话定位：这是**材质包兼容层**，不是「把 CozyUI 的观感移植进 1.12.2」。
> 模组自己不带任何美术资源，画什么完全取决于当前材质包提供了什么。

## 两条硬红线

1. **不把观感强加给原版 / 其它材质包。** 每一处替换的前提都是「材质包确实提供了这个资源」
   （去资源栈顶取，包名是 `Default` 就说明没人覆盖），没有就原样回落 1.12.2 的行为。
2. **全局性改动默认 opt-in 或默认等价。** 会改变原版表现的开关（例如字体平滑）默认关闭；
   隐藏容器标题这类「对齐 1.21.2」的改动也只在材质包覆盖了该容器底图时生效。

## 为什么必须是 coremod

1.12.2 没有 Mixin，也没有 1.13+ 的 sprite 管线（`textures/gui/sprites/**`）。所以本分支用
FML 的 coremod（`TooltipFixCore` + `IClassTransformer`）在类加载期改写原版方法，按注入点分四类：

| Transformer | 目标 | 手法 |
| --- | --- | --- |
| `GuiTransformer` | `Gui.func_73729_b/73733_a/73734_a`（GUI 三个基础图元） | 整方法体替换 |
| `GuiScreenTransformer` | `GuiScreen.func_146279_a`(3 参) / `func_146285_a`(4 参 tooltip) | 整方法体替换 |
| `PanoramaTransformer` | `GuiMainMenu` 里画全景图的私有 `(IIF)V` | 整方法体替换 |
| `FontRendererTransformer` | `FontRenderer.a(CZ)F` / `a(C)I` / `a(String)I` / `a(String;III)I` | 改名 + 生成 wrapper |
| `ResourcePackUiTransformer` | 「资源包」界面 `drawScreen` 尾部 | 插一条 `INVOKESTATIC`，画设置入口 |

注意：transformer 跑在 `FMLDeobfuscatingRemapper` **之前**，看到的是混淆名（`bip`、`a`、`func_73729_b` 的
srg 名），所以五个 transformer 全部靠**结构匹配**（方法描述符 + 方法体里的特征常量）定位，不能硬编码类名。
匹配不上的时候一个字节都不动——宁可不生效，也不能改坏别人的界面。

## 目录结构

```
1.12.2/
├── build.ps1                       # 编译 + 打包（不依赖 ForgeGradle / 联网）
└── src/
    ├── resources/mcmod.info
    └── java/meow/tooltipfix/
        ├── TooltipFixMod.java      # @Mod 入口：注册资源重载监听、tick 钩子
        ├── core/                   # 5 个 transformer + TooltipFixCore
        └── client/
            ├── Hooks.java          # 贴图分派链入口（被 transformer 调用）
            ├── GuiSprite.java      # 材质包 sprite 的加载/缓存/九宫格/动画帧
            ├── GuiRender.java      # 底层 quad 绘制 + GL 状态快照/还原
            ├── HudSprites.java     # HUD：快捷栏、血量/饥饿、准星、攻击指示器
            ├── WidgetSprites.java  # 按钮/输入框/滚动条
            ├── CreativeTabSprites  # 创造物品栏标签页
            ├── EffectSprites.java  # 药水效果图标与背景
            ├── TooltipSprites.java # tooltip 背景/边框（九宫格）
            ├── SlotSprites.java    # 物品格
            ├── LegacySheets.java   # 老式整图图集（4x HD）的 UV 重映射
            ├── Panorama.java       # 主菜单全景图
            ├── HdFont.java         # HD 字体引擎（bitmap / ttf / legacy ascii）
            ├── AsciiLayout.java    # 原版 ascii.png 的 16x16 网格 → 字符表
            ├── FontSmoothing.java  # 字体线性过滤（opt-in）
            ├── TitleFilter.java    # 隐藏容器 GUI 标题
            ├── TfSettings.java     # 设置本体：读写 config/ + 通知各模块
            ├── GuiTooltipFixSettings.java # 设置界面（开关 / 字号 / 偏移 / 间距）
            ├── FontScaleSlider.java# 字号滑块（自绘，不进 buttonList）
            ├── TooltipFixOverlay.java # 资源包界面上的入口按钮 + 滑块
            └── SelfTest.java       # 自测：自动截图流水线
```

## 构建

1.12.2 的 srg 命名 MC jar 不能随仓库分发，需要本地生成一份
（`net.minecraft:client:1.12.2:srg` + Forge 的 `forge-1.12.2-14.23.5.2864-srg` 合并成
`client-1.12.2-srg.jar`）。`build.ps1` 会自动找 `-SrgJar` → 环境变量 `TOOLTIPFIX_SRG` →
`1.12.2\libs\` → 相邻的 `1.12.2-port\tools\`，都没命中就直接报错并列出试过的路径。然后：

```powershell
# 只编译打包 → build/libs/tooltipfix-1.0.0.jar
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

# 顺便部署进本地 1.12.2 实例的 mods/
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Deploy
```

依赖清单直接从启动器的 profile json（`versions\1.12.2-Forge_14.23.5.2864\*.json`）读，
保证和运行期是同一批 jar（1.12.2 是 LWJGL **2**，手写清单最容易在这里翻车）。
编译固定 `--release 8`：1.12.2 跑在 Java 8，class 版本必须 ≤ 52。

脚本里只剩两个本机路径：`-LibRoot`（默认 `D:\mc\.minecraft\libraries`）与 `-ModsDir`
（默认本地测试实例）。`-ForgeJar` / `-ProfileJson` 留空时会从这两个推出来，换机器时按需覆盖：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Deploy `
  -LibRoot "$env:APPDATA\.minecraft\libraries" `
  -ModsDir "$env:APPDATA\.minecraft\mods"
```

## 核心机制

### 1. 贴图分派链

原版所有 GUI 绘制最终都落到 `Gui.drawTexturedModalRect` / `drawModalRectWithCustomSizedTexture`
/ `drawRect`。transformer 把方法体换成 `Hooks.blit(...)`，由它按顺序问：

```
Debug → passthrough → WidgetSprites → HudSprites → CreativeTabSprites
      → EffectSprites → LegacySheets → GuiRender.rawBlit（原版行为）
```

每一层靠 **(u, v, w, h) + 当前绑定贴图** 认出「这是哪块 GUI」，命中就画材质包对应的 sprite，
否则往下传。整条链包在 `try/catch(report)/finally(GuiRender.rebind(previousTexture))` 里：
出错只记一次日志、绘制结果退化成原版，绝不让 GUI 直接崩掉。

### 2. 反色混合律（本分支最重要的技术结论）

1.12.2 的 `GuiIngame.func_175180_a` 是在
`GlStateManager.func_187428_a(ONE_MINUS_DST_COLOR, ONE_MINUS_SRC_COLOR, ONE, ZERO)`
状态下画准星和准星侧攻击指示器的：

```
out = src + dst - 2 * src * dst
```

即 **纯黑是 no-op（= 透明），纯白会把背景反色**。CozyUI 的
`crosshair.png` / `crosshair_attack_indicator_{background,progress,full}.png` 是
**IHDR colortype 2（RGB，没有 alpha 通道）**、黑底白图，正是按这个公式设计的。
所以这几张图必须走 `GuiSprite.drawKeepBlend`（→ `GuiRender.spriteKeepBlend`）：
**只换贴图、不碰 blend 函数**。一旦改成普通 alpha 混合，黑底会变成黑方块。
同理，`spriteKeepBlend` 里要显式 `enableBlend()`——原版那行 `func_179141_d()` 是
`enableAlpha`（不是 `enableBlend`），混合开关全靠前面 HUD 留下的状态。

### 3. HD 字体引擎 `HdFont`

1.12.2 的 `FontRenderer` 只认 128×128 的 `textures/font/ascii.png`：
`func_78266_a(int, boolean)` 的 UV 写死 `/128.0f`、格子写死 8px。所以材质包给的
1.13+ 风格字体（`font/default.json` + 一张张 `mcsans_*.png`）在 1.12.2 上根本画不出来。

`HdFont` 直接解析 `assets/minecraft/font/default.json` 的 bitmap provider（`file` / `height` /
`ascent` / `chars`），自己建图集、算墨迹宽度、按 `cellW/cellH → drawW/drawH` 缩放，
最后由 `FontRendererTransformer` 把 `draw` / `advance` / `stringWidth` 三个方法换掉。
`DENSITY`（`-Dtooltipfix.hdfont.density`，默认 4）控制重采样倍率：默认取材质包原分辨率
（guiScale 4 时 1:1），不额外降采样。

**ttf provider 也支持**：现代字体包（如 `cym_Cosmos_Glyphs字体包`）只给一行
`{"type":"ttf","file":"…ttf","size":5.5,"oversample":8}`，1.12.2 完全没有这套东西。
`HdFont.parseTtf()` 用 `java.awt.Font.createFont()` 把 ttf 载进来，按 `size × oversample`
设 em、用 `GlyphVector` 取每字形的墨迹矩形与 advance，`oversample` 倍超采样后 1:1 拷进
一张 2048² 货架式图集（`glTexSubImage2D` 增量上传），度量再除以 `oversample` 还原成逻辑像素。
注意 `file` 是相对 `assets/<ns>/` 的路径，**要补 `font/` 前缀**才是资源位置。

**逐字形回退**：`HdFont.has(char)` / `covers(String)` 在字形缺失（`GLYPHS` 没命中、且 ttf 的
`canDisplay` 为 false）时返回 false，transformer 生成的包装器随即改调 `meow$drawVanilla`，
于是「字体包没有的字」（例如纯拉丁装饰字体里的中文）自动交回原版 unicode 页，不会出现豆腐块。

### 4. 第三方 legacy 字体包也能覆盖

只认 `default.json` 会让「后加载的字体包」失效——例如
`Slightly-Improved-Font-1.1.5.zip` 只提供 `textures/font/ascii.png`（256×256，2×），
没有 `default.json`，而原版管线又只认 128×128，于是它永远画不出来。

`HdFont.legacyAscii()` 会去资源栈顶取 `textures/font/ascii.png`：只要不是 `Default`
（= 真的被某个材质包覆盖了），就把它当成一个 16×16 网格的 bitmap provider，
**插到 provider 列表的第一位**。`HdFont` 的 glyph 表是「先到先得」，于是：

* 拉丁字符 → 走字体包的 `ascii.png`（用户想看到的效果）；
* CJK / emoji → 继续由 CozyUI 的 `default.json` 兜底（`AsciiLayout.GRID` 提供原版 256 格
  「网格序号 → 字符」映射，从原版 jar 的常量池里机械导出）。

只有 `ascii.png`、没有 `default.json` 时（单独加载一个老式字体包），`HdFont` 照样接管，
只是 provider 列表里只有这一项：拉丁走字体包，CJK 逐字形回退原版。只有「既没有
`default.json`、`ascii.png` 也没被覆盖、也没有 ttf」时才 `return` 保持原版管线。

### 5. 隐藏容器标题 `TitleFilter`

1.12.2 的容器界面会在底图上用深灰 `4210752` 画两行字：顶部容器名（合成 / 熔炉 / 箱子…）
和底部「物品栏」。1.13+ 已经不画了，材质包也就按「没有这两行」设计底图；于是 CozyUI 那种
浅灰浮起面板上，这两行深灰小字几乎看不清，还压住面板圆角。

判据是「**覆盖**」而不是「类名」：`LegacySheets.blit` 画到 `textures/gui/container/*.png`
且栈顶不是 `Default` 时，把当前 `GuiScreen` 记进 `COVERED`；`TitleFilter.hidden()` 只对这张
屏幕生效，并且只吞 `y <= 8`（所有容器标题都在这条带里）和 `y == ySize - 94`（底部物品栏标签）。
创造物品栏在 `(8, 6)` 画的是**标签页名字**，1.21.2 也照画，所以单独放行。

### 6. 主菜单全景图

1.12.2 只认 `textures/gui/title/background/panorama_*.png`；`PanoramaTransformer` 换掉
`GuiMainMenu` 里那段画全景图的私有方法，让 6 张图按现代方式铺满并正确旋转。

## 自测

```powershell
# 自动跑一整轮：进测试世界 → 逐屏截图 → 退出
java -Dtooltipfix.selftest=true -Dtooltipfix.debug=true ...
```

截图落在 `screenshots\selftest-*.png`（`00-mainmenu` / `01-hud` / `02-inventory-*` /
`03-creative-*` / `04-tooltip-*` / `05-world` / `06-itemtooltip-*` / `07-cooldown` /
`08-attack-indicator` / `09-resourcepacks-overlay` / `10~14-settings-*`），每个界面都成对截「装了模组」和
`-Dtooltipfix.passthrough=true`（纯原版行为）两张，方便直接 A/B。日志里 grep `[tooltipfix]` 就能看到每个
sprite 来自哪个包。自测跑完会把设置复位成默认值，不会把测试用的字号/偏移留给下一次启动。

> 自测里直接用 `TfSettings.setXxx()` 改值，绕过了按钮回调 `refreshLabels()`，所以那几张图里按钮文字
> 可能和实际状态不同步；手点不会出现这种情况。

## 开关一览

游戏内入口：**资源包（材质包）界面左下角「TooltipFix 设置…」**——里面有字体引擎、隐藏容器标题、
CozyUI 预设三个开关，以及字号滑块、字形 X/Y 偏移、字间距，改完立刻生效并写入
`config/tooltipfix-hdfont.txt`。字体引擎关掉之后这个按钮仍然在（不然就再也打不开了）。

字号、偏移、间距都是给「材质包 ttf 与原版中文混排」准备的：字号解决大小，偏移解决**基线/字高对不齐**
（拉丁走 ttf、中文回退 unifont 时常见一高一低），间距解决**字贴太紧或太松**。

* 偏移只平移 HD 字形本身、**不改前进宽度**，所以不会挤乱排版；步进整数像素，避免半像素落在
  GL_LINEAR 上糊成两像素。
* 间距改的是前进宽度（`advance`），绘制返回值与 `getStringWidth` 用的是同一个函数，所以居中/右对齐
  不会错位；结果夹到 `>= 0`，调成很负也只是字挨在一起，不会让光标往回走。

| 系统属性 | 默认 | 作用 |
| --- | --- | --- |
| `-Dtooltipfix.debug` | 关 | 打开 `[tooltipfix]` 详细日志 |
| `-Dtooltipfix.passthrough` | 关 | 全部 sprite 直通原版（最快 A/B：判断「这块是不是我们画的」） |
| `-Dtooltipfix.hdfont=false` | 开 | 字体引擎的启动默认值（游戏内设置界面可随时再打开） |
| `-Dtooltipfix.hdfont.density=N` | 4 | 字体重采样倍率（4 = 材质包原分辨率） |
| `-Dtooltipfix.hdfont.ttfscale=N` | 自动 | ttf 放大倍率；不设 = 自动抬到 6.5px 逻辑字号的下限再乘字号滑块，`1` = 严格按包的 size，`2` = 两倍 |
| `-Dtooltipfix.hdfont.scale=N` | 1.0 | 字号倍率的启动默认值（等同于设置界面里的滑块，范围 0.5–2.0） |
| `-Dtooltipfix.fontsmooth` | 关 | 老式 128×128 字体走线性过滤（默认最近邻，更锐利） |
| `-Dtooltipfix.hidetitles=false` | 开 | 隐藏容器标题的启动默认值（游戏内可切） |
| `-Dtooltipfix.selftest` | 关 | 自动截图流水线 |
| `-Dtooltipfix.selftest.world=名字` | `tooltipfix-test` | 自测用世界名 |

`config/tooltipfix-hdfont.txt` 的键：`font=on|off`、`scale=1.00`、`titles=on|off`、
`offset.x=0`、`offset.y=0`、`spacing=0`、`preset=on|off`（以及 `preset.*` 一组还原用的原值）。
CozyUI 预设 = 字体引擎开 + 字号 100% + 隐藏容器标题 + 偏移/间距全 0，关掉预设会还原打开前的各项。

## 已知限制

* **emoji**：CozyUI 的 `emoji_05_*.png` 依赖 `font/default.json` 里的 emoji provider；
  少数 codepoint 在 1.12.2 的 unicode 页面里缺页（`unicode_page_0xE5` 一带），显示为缺字方块。
* **主菜单标题 logo**：CozyUI 没有 `title/minecraft.png`，所以仍用原版 logo（用户决定不动）。
* **老式整图图集**（1024×1024 的 4× HD `textures/gui/*.png`）靠 `LegacySheets` 重算 UV，
  只对「按原版 UV 画」的图元有效；材质包若把整张图重排过版式，仍需要单独适配。
* **ttf 字形缺失**：字体包只含拉丁/希腊字母时（例如 `cym_Cosmos_Glyphs`，CJK 0/20902 字形），
  中文按 `canDisplay` 逐字回退原版 unicode 页，不会出现豆腐块，但也不会变成该字体的风格。
* **ttf 格式**：走 AWT 的 `Font.createFont`，TrueType 稳定；CFF/OTF 依赖 JRE（游戏用
  jre-legacy = Java 8）是否支持，失败时打 `hdfont: ttf load failed` 并退回原版。
