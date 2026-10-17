# Builds tooltipfix for Fabric 1.21.1.
#
# No Gradle: javac compiles against official-name (named) 1.21.1 classes, then tools/Remap.java
# rewrites the whole jar into Fabric's intermediary namespace. The mixins use remap=false with the
# literal intermediary names in meow/tooltipfix/LoaderNames.java, so the jar needs no refmap.
#
# Requirements that are NOT in this repository (see README.md):
#   -NamedClasses <dir>              named 1.21.1 Minecraft classes (auto-detected in the NeoGradle
#                                    cache when omitted)
#   $PortRoot\tools\maps\named-intermediary.tiny
#   $PortRoot\tools\asm-cp.txt       paths to the ASM 9.7 jars (copy tools\asm-cp.txt.example)
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as the system ANSI codepage
# unless the file has a UTF-8 BOM, so non-ASCII comments here corrupt the script.
param(
    [string]$PortRoot = $PSScriptRoot,
    [string]$ProjectRoot = $PSScriptRoot,
    [string]$McRoot = (Join-Path $env:APPDATA ".minecraft"),
    [string]$NamedClasses = "",
    [string]$Version = "1.0.4"
)

$ErrorActionPreference = "Stop"
function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

$libs = Join-Path $McRoot "libraries"
$remapClasses = Join-Path $PortRoot "tools\remap-build"
$asmCpFile = Join-Path $PortRoot "tools\asm-cp.txt"
if (-not (Test-Path -LiteralPath $asmCpFile)) {
    throw "missing dependency: $asmCpFile - copy tools\asm-cp.txt.example to tools\asm-cp.txt and list the ASM 9.7 jar paths in it, one per line"
}
$asmCp = @(Get-Content -LiteralPath $asmCpFile | Where-Object { $_.Trim() -ne "" } | ForEach-Object { $_.Trim() })
$mixinJar = Join-Path $libs "net\fabricmc\sponge-mixin\0.17.4+mixin.0.8.7\sponge-mixin-0.17.4+mixin.0.8.7.jar"
$gsonJar = Join-Path $libs "com\google\code\gson\gson\2.10.1\gson-2.10.1.jar"
$slf4jJar = Join-Path $libs "org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"
$lwjglJar = Join-Path $libs "org\lwjgl\lwjgl-opengl\3.3.3\lwjgl-opengl-3.3.3.jar"
$mapFile = Join-Path $PortRoot "tools\maps\named-intermediary.tiny"

if (-not $NamedClasses) {
    # The NeoGradle decompile/recompile cache holds the fully named 1.21.1 joined classes.
    $probe = Get-ChildItem "$env:USERPROFILE\.gradle\caches\ng_execute" -Recurse -Directory -Filter "output" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "net\minecraft\client\gui\GuiGraphics.class") } |
        Select-Object -First 1
    if ($probe) { $NamedClasses = $probe.FullName }
}
if (-not $NamedClasses) {
    throw "could not find the named 1.21.1 classes - pass -NamedClasses <dir> (a directory holding net\minecraft\...)"
}

foreach ($f in @($mixinJar, $gsonJar, $slf4jJar, $lwjglJar, $mapFile, $NamedClasses)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "missing dependency: $f" }
}

$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
$jarTool = Join-Path $env:JAVA_HOME "bin\jar.exe"
if (-not (Test-Path $javac)) { $javac = "javac"; $jarTool = "jar"; $java = "java" }

if (-not (Test-Path -LiteralPath $remapClasses)) {
    Step "compile tools/Remap.java"
    New-Item -ItemType Directory -Force -Path $remapClasses | Out-Null
    & $javac -encoding UTF-8 --release 17 -nowarn -proc:none -cp ($asmCp -join ";") -d $remapClasses (Join-Path $PortRoot "tools\Remap.java")
    if ($LASTEXITCODE -ne 0) { throw "failed to compile tools/Remap.java" }
}
$remapCp = "$remapClasses;" + ($asmCp -join ";")

$build = Join-Path $ProjectRoot "build"
$commonSrc = Join-Path $ProjectRoot "src\common\java"
$commonRes = Join-Path $ProjectRoot "src\common\resources"
$fabricSrc = Join-Path $ProjectRoot "src\fabric\java"
$fabricRes = Join-Path $ProjectRoot "src\fabric\resources"

Step "compile"
$out = Join-Path $build "classes"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$cp = (@($NamedClasses, $mixinJar, $gsonJar, $slf4jJar, $lwjglJar) | Select-Object -Unique) -join ";"
$sources = @()
$sources += Get-ChildItem $commonSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $fabricSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 --release 21 -nowarn -proc:none -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Step "package pre-remap classes"
$pre = Join-Path $build "pre.jar"
if (Test-Path $pre) { Remove-Item $pre -Force }
& $jarTool --create --file $pre -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Step "remap whole jar (named -> intermediary)"
$remapped = Join-Path $build "remapped.jar"
if (Test-Path $remapped) { Remove-Item $remapped -Force }
& $java -cp $remapCp Remap $pre $remapped $mapFile named intermediary
if ($LASTEXITCODE -ne 0) { throw "remap failed" }

Step "assemble final jar"
$stage = Join-Path $build "stage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($remapped, $stage)
Copy-Item (Join-Path $commonRes '*') $stage -Recurse -Force
Copy-Item (Join-Path $fabricRes '*') $stage -Recurse -Force
$final = Join-Path $build "tooltipfix-1.21.1-fabric-$Version.jar"
if (Test-Path $final) { Remove-Item $final -Force }
& $jarTool --create --file $final -C $stage .
if ($LASTEXITCODE -ne 0) { throw "final jar failed" }

Step "built $final ($((Get-Item $final).Length) bytes)"
