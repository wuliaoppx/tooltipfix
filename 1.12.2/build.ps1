# 1.12.2 的构建脚本（不依赖 ForgeGradle / 网络）。
#
# 为什么不走 ForgeGradle：它要下载整套 MDK、跑 setupDecompWorkspace，在这个离线环境里
# 既慢又容易卡在 gradle 版本上；而我们的代码只需要「srg 名字的 MC 类」+ Forge + 几个库，
# 直接用本机已装好的 1.12.2 运行库编译就行。
#
# 用法：
#   pwsh -File build.ps1                 # 编译 + 打包到 build/libs/tooltipfix-1.0.0.jar
#   pwsh -File build.ps1 -Deploy         # 另外把 jar 丢进 1.12.2 实例的 mods/
#
# 编译用 --release 8：1.12.2 运行在 Java 8，class 文件版本必须 ≤ 52。

[CmdletBinding()]
param(
    # srg 命名的 MC jar 不能随仓库分发。默认留空 = 自动在几个常见位置找
    # （环境变量 TOOLTIPFIX_SRG / 仓库内 libs\ / 本地工作目录 1.12.2-port\tools\）。
    [string]$SrgJar   = "",
    # 下面三个是本机实例路径，换机器时用同名参数覆盖；留空的从 LibRoot / ModsDir 推。
    [string]$LibRoot   = "D:\mc\.minecraft\libraries",
    [string]$ModsDir  = "D:\mc\.minecraft\versions\1.12.2-Forge_14.23.5.2864\mods",
    [string]$ForgeJar = "",
    [string]$ProfileJson = "",
    [switch]$Deploy
)

# ---- 派生路径 ------------------------------------------------------------
if (-not $ForgeJar) {
    $ForgeJar = Join-Path $LibRoot 'net\minecraftforge\forge\1.12.2-14.23.5.2864\forge-1.12.2-14.23.5.2864.jar'
}
if (-not $ProfileJson) {
    # 启动器把 profile 的 json 放在版本目录里、与目录同名
    $verDir = Split-Path $ModsDir -Parent
    $ProfileJson = Join-Path $verDir ((Split-Path $verDir -Leaf) + '.json')
}

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out  = Join-Path $root 'build'
$cls  = Join-Path $out 'classes'
$jarPath = Join-Path $out 'libs\tooltipfix-1.0.0.jar'

# ---- srg jar 定位 --------------------------------------------------------
if (-not $SrgJar) {
    $candidates = @(
        $env:TOOLTIPFIX_SRG,
        (Join-Path $root 'libs\client-1.12.2-srg.jar'),
        (Join-Path $root '..\..\1.12.2-port\tools\client-1.12.2-srg.jar')
    ) | Where-Object { $_ }
    $SrgJar = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $SrgJar) {
        throw ("找不到 srg 命名的 MC jar。用 -SrgJar <路径> 指定，或设环境变量 TOOLTIPFIX_SRG。`n" +
               "  已尝试：`n    " + ($candidates -join "`n    ") + "`n" +
               "  （= net.minecraft:client:1.12.2:srg 与 forge-1.12.2-14.23.5.2864-srg 合并后的类）")
    }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $cls -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $jarPath) -Force | Out-Null

# ---- 依赖 ---------------------------------------------------------------
# 直接从启动器的 profile json 读库清单，保证和运行期是同一批 jar
# （1.12.2 用的是 LWJGL 2.9.4，不是 LWJGL 3，手写清单最容易在这里翻车）。
$libs = @()
if (Test-Path $ProfileJson) {
    $profile = Get-Content $ProfileJson -Raw | ConvertFrom-Json
    foreach ($lib in $profile.libraries) {
        $rel = $null
        if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.path) {
            $rel = $lib.downloads.artifact.path
        } elseif ($lib.name) {
            $parts = $lib.name.Split(':')
            if ($parts.Count -ge 3) {
                $group = $parts[0] -replace '\.', '/'
                $artifact = $parts[1]
                $version = $parts[2]
                $rel = "$group/$artifact/$version/$artifact-$version.jar"
            }
        }
        if ($rel) { $libs += (Join-Path $LibRoot ($rel -replace '/', '\')) }
    }
    $libs = $libs | Where-Object { Test-Path $_ } | Select-Object -Unique
} else {
    Write-Warning "找不到 profile json（$ProfileJson），退回手写依赖清单"
    $libs = @(
        (Join-Path $LibRoot 'net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar'),
        (Join-Path $LibRoot 'org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar'),
        (Join-Path $LibRoot 'org\lwjgl\lwjgl\lwjgl_util\2.9.4-nightly-20150209\lwjgl_util-2.9.4-nightly-20150209.jar'),
        (Join-Path $LibRoot 'org\apache\logging\log4j\log4j-api\2.8.1\log4j-api-2.8.1.jar'),
        (Join-Path $LibRoot 'com\google\code\gson\gson\2.8.0\gson-2.8.0.jar'),
        (Join-Path $LibRoot 'com\google\guava\guava\21.0\guava-21.0.jar')
    ) | Where-Object { Test-Path $_ }
}

# ASM：profile 里指向 asm-all，但本机可能只剩分离版 asm，退一个能用的。
if (-not ($libs | Where-Object { $_ -match 'asm-all|asm-5' })) {
    $asm = Get-ChildItem (Join-Path $LibRoot 'org\ow2\asm\asm') -Recurse -Filter 'asm-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object Name -Descending | Select-Object -First 1
    if ($asm) { $libs += $asm.FullName }
}

$missing = $libs | Where-Object { -not (Test-Path $_) }
if ($missing) {
    throw "缺少依赖 jar：`n  $($missing -join "`n  ")"
}
if (-not (Test-Path $SrgJar))   { throw "缺少 MC srg jar：$SrgJar" }
if (-not (Test-Path $ForgeJar)) { throw "缺少 Forge jar：$ForgeJar" }

$cp = @($SrgJar, $ForgeJar) + $libs

# ---- 编译 ---------------------------------------------------------------
$sources = Get-ChildItem (Join-Path $root 'src\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
Write-Host "编译 $($sources.Count) 个源文件 ..."

$argsList = @(
    '-J-Duser.language=en',
    '-J-Duser.country=US',
    '--release', '8',
    '-encoding', 'UTF-8',
    '-nowarn',
    '-cp', ($cp -join ';'),
    '-d', $cls
) + $sources

& javac @argsList
if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit $LASTEXITCODE）" }

# ---- 资源 + 清单 ---------------------------------------------------------
$resDir = Join-Path $root 'src\resources'
if (Test-Path $resDir) {
    Copy-Item (Join-Path $resDir '*') $cls -Recurse -Force
}

# coremod 的两个属性必须写在 manifest 里，FML 只认这里。
$manifest = @(
    'Manifest-Version: 1.0',
    'FMLCorePlugin: meow.tooltipfix.core.TooltipFixCore',
    'FMLCorePluginContainsFMLMod: true',
    ''
) -join "`n"
$manifestPath = Join-Path $out 'MANIFEST.MF'
# 清单不能带 BOM，否则 jar 工具报 invalid header field name。
[System.IO.File]::WriteAllText($manifestPath, $manifest, (New-Object System.Text.UTF8Encoding($false)))

# ---- 打包 ---------------------------------------------------------------
& jar --create --file $jarPath --manifest $manifestPath -C $cls .
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败（exit $LASTEXITCODE）" }

$size = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host "OK -> $jarPath ($size KB)"

if ($Deploy) {
    if (-not (Test-Path $ModsDir)) { New-Item -ItemType Directory -Path $ModsDir -Force | Out-Null }
    Copy-Item $jarPath $ModsDir -Force
    Write-Host "已部署到 $ModsDir"
}
