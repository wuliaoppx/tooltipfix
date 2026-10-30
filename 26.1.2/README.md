# TooltipFix —— 26.1.2 系统提示框（toast）修复

本变体**只修系统提示框（toast）的绘制**，不替换物品悬浮框。与 `1.21.11` 变体同源，
差别只在游戏版本 API（26.1.2 的 toast 入口是 `SystemToast.extractRenderState`）。

## 修的是什么

原版 `GuiGraphicsExtractor.blitNineSlicedSprite` 把每条边框单独夹到目标尺寸的一半
（`min(border, w/2)` / `min(border, h/2)`），而材质包（如 CozyUI）的 `toast/system.png.mcmeta`
写的是 `border.top = 26`：单行提示（32 高）被夹到 16、两行提示（44 高）被夹到 22，
于是「!」图标被纵向压扁，且同一张贴图在不同高度的提示上压缩率不一样。

本模组按**统一比例**缩放整张九宫格（`k = min(w/baseW, h/baseH)`，边框不再逐条夹断），
宽度或高度变化时只拉伸中段，四角与图标保持原比例。

## 兼容的版本

| 目录 | 加载器 |
| --- | --- |
| `src/neoforge` | NeoForge 26.1.x |
| `src/fabric` | Fabric Loader 0.16+ |

## 构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-25'
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Deploy
```

构建依赖（不进仓库）：26.1.2 客户端 jar、sponge-mixin、gson、joml、slf4j、brigadier、
datafixerupper（NeoForge 另需 fancymodloader）。路径在 `build.ps1` 顶部的变量里改。

## 配置

`config/tooltipfix-toast.txt`，单行取值（0.25 秒轮询）：

| 值 | 行为 |
| --- | --- |
| `fix`（默认） | 用本模组的等比九宫格 |
| `clamp` | 走原版逻辑，用来做 A/B 对照 |
| `off` | 完全不干预 |

## 许可

[MIT](../LICENSE)
