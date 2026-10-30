param(
    [ValidateSet('neoforge', 'fabric', 'both')]
    [string]$Variant = 'both',
    [switch]$Deploy
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = 'C:\Program Files\Zulu\zulu-21\bin'
$javac = Join-Path $jdk 'javac.exe'
$jar = Join-Path $jdk 'jar.exe'
$java = Join-Path $jdk 'java.exe'

# 1.21.11 ships obfuscated classes; the named jar and the named -> intermediary
# mapping were produced once with proguard2tiny/compose1211 + tiny-remapper.
$mc = 'D:\mc\.minecraft\versions\_tmpjavap\named\1.21.11-named.jar'
$mixin = 'D:\mc\.minecraft\libraries\net\fabricmc\sponge-mixin\0.16.5+mixin.0.8.7\sponge-mixin-0.16.5+mixin.0.8.7.jar'
$gson = 'D:\mc\.minecraft\libraries\com\google\code\gson\gson\2.13.2\gson-2.13.2.jar'
$joml = 'D:\mc\.minecraft\libraries\org\joml\joml\1.10.8\joml-1.10.8.jar'
$slf4j = 'D:\mc\.minecraft\libraries\org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar'
$brig = 'D:\mc\.minecraft\libraries\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar'
$datafix = 'D:\mc\.minecraft\libraries\com\mojang\datafixerupper\9.0.19\datafixerupper-9.0.19.jar'
$fml = 'D:\mc\.minecraft\libraries\net\neoforged\fancymodloader\loader\10.0.36\loader-10.0.36.jar'
$interTiny = Join-Path $root 'tools\named-intermediary-1.21.11.tiny'
$remapper = 'C:\Users\wuliao\Documents\dsh文件夹\tooltipfix项目文件夹\1.20.1-port\tools\tiny-remapper-0.10.4-fat.jar'

foreach ($p in @($mc, $mixin, $gson, $joml, $slf4j, $brig, $datafix)) {
    if (-not (Test-Path $p)) { throw "missing dependency: $p" }
}

$common = Join-Path $root 'src\common\java'
$commonRes = Join-Path $root 'src\common\resources'

function Build-Variant {
    param([string]$Name)

    $extraJava = Join-Path $root "src\$Name\java"
    $extraRes = Join-Path $root "src\$Name\resources"
    $classes = Join-Path $root "build\classes-$Name"
    if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $files = @()
    $files += Get-ChildItem $common -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    $files += Get-ChildItem $extraJava -Recurse -Filter *.java | ForEach-Object { $_.FullName }

    $deps = @($mc, $mixin, $gson, $joml, $slf4j, $brig, $datafix)
    if ($Name -eq 'neoforge') {
        if (-not (Test-Path $fml)) { throw "missing dependency: $fml" }
        $deps += $fml
    }
    $cp = $deps -join ';'

    & $javac -proc:none --release 21 -encoding UTF-8 -nowarn -classpath $cp -d $classes @files
    if ($LASTEXITCODE -ne 0) { throw "javac failed ($Name)" }

    Copy-Item (Join-Path $commonRes '*') $classes -Recurse -Force
    Copy-Item (Join-Path $extraRes '*') $classes -Recurse -Force

    $out = Join-Path $root "build\tooltipfix-1.0.0-$Name.jar"
    if (Test-Path $out) { Remove-Item $out -Force }
    & $jar --create --file $out -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar failed ($Name)" }

    if ($Name -eq 'fabric') {
        if (-not (Test-Path $interTiny)) { throw "missing intermediary mapping: $interTiny" }
        $remapped = Join-Path $root "build\tooltipfix-1.0.0-fabric-remapped.jar"
        if (Test-Path $remapped) { Remove-Item $remapped -Force }
        & $java -jar $remapper $out $remapped $interTiny named intermediary $mc
        if ($LASTEXITCODE -ne 0) { throw 'intermediary remap failed' }
        Move-Item $remapped $out -Force
    }

    Write-Host ("built {0} bytes -> {1}" -f (Get-Item $out).Length, $out)

    if ($Deploy) {
        $dest = if ($Name -eq 'fabric') {
            'D:\mc\.minecraft\versions\1.21.11-Fabric\mods'
        } else {
            'D:\mc\.minecraft\versions\1.21.11-NeoForge\mods'
        }
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $target = Join-Path $dest ("tooltipfix-1.0.0-{0}.jar" -f $Name)
        Copy-Item $out $target -Force
        Write-Host "deployed -> $target"
    }
}

if ($Variant -eq 'both') {
    Build-Variant 'neoforge'
    Build-Variant 'fabric'
} else {
    Build-Variant $Variant
}
