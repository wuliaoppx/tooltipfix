# Builds the 1.20.1 variants of tooltipfix (one jar for Forge 47.4.26, one for Fabric).
#
# No Gradle: javac compiles against a named (mojmap) 1.20.1 jar, then tools/Remap.java rewrites the
# whole jar into each loader's runtime namespace (Forge: named -> SRG, Fabric: named -> intermediary).
# The mixins use remap=false with literal runtime names, so neither jar needs a refmap.
#
# Requirements that are NOT in this repository (see README.md):
#   $PortRoot\1.20.1-named.jar       named/decompiled 1.20.1 client+server classes
#   $PortRoot\tools\maps\named-srg.tiny, named-intermediary.tiny
#   $PortRoot\tools\asm-cp.txt       paths to the ASM 9.7 jars (copy tools\asm-cp.txt.example)
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as the system ANSI codepage
# unless the file has a UTF-8 BOM, so non-ASCII comments here corrupt the script.
param(
    [string]$PortRoot = $PSScriptRoot,
    [string]$ProjectRoot = $PSScriptRoot,
    [string]$McRoot = (Join-Path $env:APPDATA ".minecraft"),
    [string]$ForgeVersion = "47.4.26",
    [string]$Version = "1.0.28"
)

$ErrorActionPreference = "Stop"
function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

$libs = Join-Path $McRoot "libraries"
$namedJar = Join-Path $PortRoot "1.20.1-named.jar"
# Custom ASM remapper (tools/Remap.java). tiny-remapper silently skips classes whose name is
# identical in both namespaces, which is exactly the Forge named->SRG case.
$remapClasses = Join-Path $PortRoot "tools\remap-build"
$asmCpFile = Join-Path $PortRoot "tools\asm-cp.txt"
if (-not (Test-Path -LiteralPath $asmCpFile)) {
    throw "missing dependency: $asmCpFile - copy tools\asm-cp.txt.example to tools\asm-cp.txt and list the ASM 9.7 jar paths in it, one per line"
}
$asmCp = @(Get-Content -LiteralPath $asmCpFile | Where-Object { $_.Trim() -ne "" } | ForEach-Object { $_.Trim() })
$mixinJar = Join-Path $libs "net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar"
$gsonJar = Join-Path $libs "com\google\code\gson\gson\2.10.1\gson-2.10.1.jar"
$slf4jJar = Join-Path $libs "org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"
# BlendState queries the real GL_BLEND switch through LWJGL (read-only), so the compile classpath
# needs lwjgl-opengl. Keep every comment in this file ASCII - see the note in the header.
$lwjglJar = Join-Path $libs "org\lwjgl\lwjgl-opengl\3.3.2\lwjgl-opengl-3.3.2.jar"
$forgeJar = Join-Path $libs "net\minecraftforge\forge\1.20.1-$ForgeVersion\forge-1.20.1-$ForgeVersion-universal.jar"
# In Forge 1.20.1 the @Mod annotation lives in javafmllanguage, not in the universal jar.
$fmlLanguageJar = Join-Path $libs "net\minecraftforge\javafmllanguage\1.20.1-$ForgeVersion\javafmllanguage-1.20.1-$ForgeVersion.jar"

foreach ($f in @($namedJar, $mixinJar, $gsonJar, $slf4jJar, $lwjglJar, $forgeJar, $fmlLanguageJar)) {
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
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Build-Variant($label, $mapFile, $loaderSource, $loaderResource, $extraClasspath, $manifestLine) {
    Step "[$label] compile"
    $out = Join-Path $build "$label\classes"
    if (Test-Path $out) { Remove-Item $out -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $cp = @($namedJar, $mixinJar, $gsonJar, $slf4jJar, $lwjglJar) + $extraClasspath
    $cpString = ($cp | Select-Object -Unique) -join ";"

    $sources = @()
    $sources += Get-ChildItem $commonSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    $sources += Get-ChildItem $loaderSource -Recurse -Filter *.java | ForEach-Object { $_.FullName }

    & $javac -encoding UTF-8 --release 17 -nowarn -proc:none -cp $cpString -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw "[$label] javac failed ($LASTEXITCODE)" }

    Step "[$label] package pre-remap classes"
    $pre = Join-Path $build "$label\pre.jar"
    if (Test-Path $pre) { Remove-Item $pre -Force }
    & $jarTool --create --file $pre -C $out .
    if ($LASTEXITCODE -ne 0) { throw "[$label] jar failed" }

    $targetNs = (Split-Path $mapFile -Leaf).Replace('named-', '').Replace('.tiny', '')
    Step "[$label] remap whole jar (named -> $targetNs)"
    $remapped = Join-Path $build "$label\remapped.jar"
    if (Test-Path $remapped) { Remove-Item $remapped -Force }
    & $java -cp $remapCp Remap $pre $remapped $mapFile named $targetNs 2>&1 | Select-Object -Last 4
    if ($LASTEXITCODE -ne 0) { throw "[$label] remap failed" }

    Step "[$label] assemble final jar"
    $stage = Join-Path $build "$label\stage"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($remapped, $stage)
    Copy-Item (Join-Path $commonRes '*') $stage -Recurse -Force
    Copy-Item (Join-Path $loaderResource '*') $stage -Recurse -Force

    $manifestPath = Join-Path $build "$label\MANIFEST.MF"
    @("Manifest-Version: 1.0", $manifestLine, "") | Set-Content -LiteralPath $manifestPath -Encoding ASCII
    $final = Join-Path $build "tooltipfix-1.20.1-$label-$Version.jar"
    if (Test-Path $final) { Remove-Item $final -Force }
    & $jarTool --create --file $final --manifest $manifestPath -C $stage .
    if ($LASTEXITCODE -ne 0) { throw "[$label] final jar failed" }
    Step "[$label] built $final ($((Get-Item $final).Length) bytes)"
    return $final
}

$forgeOut = Build-Variant "forge" (Join-Path $PortRoot "tools\maps\named-srg.tiny") `
    (Join-Path $ProjectRoot "src\forge\java") (Join-Path $ProjectRoot "src\forge\resources") `
    @($forgeJar, $fmlLanguageJar) "MixinConfigs: tooltipfix.mixins.json,tooltipfix.forge.mixins.json"

$fabricOut = Build-Variant "fabric" (Join-Path $PortRoot "tools\maps\named-intermediary.tiny") `
    (Join-Path $ProjectRoot "src\fabric\java") (Join-Path $ProjectRoot "src\fabric\resources") `
    @() "Manifest-Version-Comment: see fabric.mod.json"

Step "done: $forgeOut / $fabricOut"
