# TooltipFix —— 1.21.11 系统提示框（toast）修复

本变体**只修系统提示框（toast）的绘制**，不替换物品悬浮框。

## 修的是什么

1.21.2 起，toast 的背景从「一张 256×256 的 `textures/gui/toasts.png` + 手工三段横切」
改成了 **GUI sprite + `.mcmeta` 九宫格**：

```
assets/minecraft/textures/gui/sprites/toast/system.png
assets/minecraft/textures/gui/sprites/toast/system.png.mcmeta   → gui.scaling.nine_slice
```

1.21.4 起原版 `GuiGraphics.blitNineSlicedSprite` 会把每条边框**单独夹到目标尺寸的一半**
（`min(border, w/2)` / `min(border, h/2)`）：

```java
int l = Math.min(border.left(),  width / 2);
int t = Math.min(border.top(),   height / 2);
```

单行系统提示只有 32 高，而材质包（如 CozyUI）给的 `border.top` 是 26 ⇒ 被夹到 16，
左上角的「!」图标就被纵向压扁；两行提示（44 高）夹到 22，压缩率又不一样。
本模组把整张九宫格按**统一比例**缩放（`k = min(w/baseW, h/baseH)`，再确保边框不被压缩），
宽度或高度变化时只拉伸中段，四角与图标保持原比例。

## 兼容的版本

| 目录 | 加载器 | 说明 |
| --- | --- | --- |
| `src/neoforge` | NeoForge 21.11.x | `neoforge.mods.toml` |
| `src/fabric` | Fabric Loader 0.16+ | `fabric.mod.json`，需要 Fabric API 与本模组无关，可不装 |

1.21.11 的客户端 jar 是**混淆**的，Fabric 侧需要一份 named→intermediary 映射；
`tools/compose1211.py` 用 Mojang 官方映射 + Fabric intermediary 生成
`named-intermediary-1.21.11.tiny`，再由 tiny-remapper 把 jar 重映射到 intermediary。

## 构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Variant both -Deploy
```

`-Variant neoforge|fabric|both`，`-Deploy` 会顺手复制到对应实例的 `mods\`。

构建依赖（不进仓库）：具名 1.21.11 客户端 jar、sponge-mixin、gson、joml、slf4j、brigadier、
datafixerupper（NeoForge 另需 fancymodloader）。路径在 `build.ps1` 顶部的变量里改。

> `build.ps1` 含中文注释，必须保存为 **UTF-8 with BOM**，否则 Windows PowerShell 5.1 会按 ANSI 读，
> 整行失效。

## 配置

`config/tooltipfix-toast.txt`，单行取值（0.25 秒轮询，改完不用重启）：

| 值 | 行为 |
| --- | --- |
| `fix`（默认） | 用本模组的等比九宫格 |
| `clamp` | 走原版逻辑（边框各自夹到目标尺寸一半），用来做 A/B 对照 |
| `off` | 完全不干预 |

## 许可

[MIT](../LICENSE)
