param(
    [switch]$Deploy,
    [string]$Only = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = 'C:\Program Files\Zulu\zulu-25\bin'
$javac = Join-Path $jdk 'javac.exe'
$jar = Join-Path $jdk 'jar.exe'

# 26.1.2 ships official (Mojang) names directly, so there is no remap step at all:
# the same class files work on Fabric and on NeoForge.
$mc = 'D:\mc\.minecraft\versions\26.1.2-Fabric\26.1.2-Fabric.jar'
$mixin = 'D:\mc\.minecraft\libraries\org\spongepowered\mixin\0.8.5\mixin-0.8.5.jar'
$gson = 'D:\mc\.minecraft\libraries\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
$joml = 'D:\mc\.minecraft\libraries\org\joml\joml\1.10.5\joml-1.10.5.jar'
$slf4j = 'D:\mc\.minecraft\libraries\org\slf4j\slf4j-api\2.0.16\slf4j-api-2.0.16.jar'
$brig = 'D:\mc\.minecraft\libraries\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar'
$datafix = 'D:\mc\.minecraft\libraries\com\mojang\datafixerupper\9.0.19\datafixerupper-9.0.19.jar'
# @Mod annotation lives in FancyModLoader, NeoForge only.
$fml = 'D:\mc\.minecraft\libraries\net\neoforged\fancymodloader\loader\11.0.15\loader-11.0.15.jar'

foreach ($p in @($mc, $mixin, $gson, $joml, $slf4j, $brig, $datafix)) {
    if (-not (Test-Path $p)) { throw "missing dependency: $p" }
}

$common = Join-Path $root 'src\common\java'
$commonRes = Join-Path $root 'src\common\resources'
$deployNeo = 'D:\mc\.minecraft\versions\26.1.2-NeoForge\mods'
$deployFab = 'D:\mc\.minecraft\versions\26.1.2-Fabric\mods'

function Build-Variant {
    param(
        [string]$Name,
        [string[]]$SourceDirs,
        [string[]]$ResourceDirs,
        [string]$Classpath
    )

    $classes = Join-Path $root "build\classes-$Name"
    if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $files = @()
    foreach ($d in $SourceDirs) {
        if (Test-Path $d) { $files += Get-ChildItem $d -Recurse -Filter *.java | ForEach-Object { $_.FullName } }
    }
    if ($files.Count -eq 0) { throw "no sources for $Name" }

    & $javac -proc:none --release 21 -encoding UTF-8 -nowarn -classpath $Classpath -d $classes @files
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $Name" }

    foreach ($d in $ResourceDirs) {
        if (Test-Path $d) { Copy-Item (Join-Path $d '*') $classes -Recurse -Force }
    }

    $out = Join-Path $root "build\tooltipfix-26.1.2-$Name.jar"
    if (Test-Path $out) { Remove-Item $out -Force }
    & $jar --create --file $out -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar failed for $Name" }

    $size = (Get-Item $out).Length
    Write-Host ("{0,-9} {1,8} bytes  {2}" -f $Name, $size, $out)
    return $out
}

$neoSrc = @($common, (Join-Path $root 'src\neoforge\java'))
$fabSrc = @($common, (Join-Path $root 'src\fabric\java'))
$neoRes = @($commonRes, (Join-Path $root 'src\neoforge\resources'))
$fabRes = @($commonRes, (Join-Path $root 'src\fabric\resources'))

$baseCp = @($mc, $mixin, $gson, $joml, $slf4j, $brig, $datafix) -join ';'

if ($Only -eq '' -or $Only -eq 'neoforge') {
    if (-not (Test-Path $fml)) { throw "missing dependency: $fml" }
    $neo = Build-Variant -Name 'neoforge' -SourceDirs $neoSrc -ResourceDirs $neoRes -Classpath ($baseCp + ';' + $fml)
    if ($Deploy) {
        New-Item -ItemType Directory -Force -Path $deployNeo | Out-Null
        Copy-Item $neo (Join-Path $deployNeo 'tooltipfix-1.0.0.jar') -Force
        Write-Host "deployed -> $deployNeo"
    }
}

if ($Only -eq '' -or $Only -eq 'fabric') {
    $fab = Build-Variant -Name 'fabric' -SourceDirs $fabSrc -ResourceDirs $fabRes -Classpath $baseCp
    if ($Deploy) {
        New-Item -ItemType Directory -Force -Path $deployFab | Out-Null
        Copy-Item $fab (Join-Path $deployFab 'tooltipfix-1.0.0.jar') -Force
        Write-Host "deployed -> $deployFab"
    }
}
