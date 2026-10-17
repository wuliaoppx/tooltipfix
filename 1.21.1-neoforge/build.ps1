# Builds tooltipfix-<version>.jar (1.21.1 NeoForge) without Gradle.
#
# The mod is a pure patch mod: it only needs the named Minecraft classes plus the NeoForge/FML
# runtime that is already installed for the 1.21.1 NeoForge instance, so plain javac + jar is enough.
# NeoForge 1.20.2+ runs with Mojang official mappings in production, which is also what these classes
# use, so mixins need no refmap.

param(
    [string]$Version = "1.0.6",
    [string]$ProjectRoot = $PSScriptRoot,
    [string]$McRoot = (Join-Path $env:APPDATA ".minecraft"),
    [string]$NamedMcClasses = ""
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# ---------------------------------------------------------------- named Minecraft + NeoForge
$libraries = Join-Path $McRoot "libraries"

if (-not $NamedMcClasses) {
    # The NeoGradle decompile/recompile cache holds the fully named 1.21.1 joined classes.
    $probe = Get-ChildItem "$env:USERPROFILE\.gradle\caches\ng_execute" -Recurse -Directory -Filter "output" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "net\minecraft\client\gui\GuiGraphics.class") } |
        Select-Object -First 1
    if (-not $probe) {
        throw "Could not find the named Minecraft classes. Pass -NamedMcClasses <dir>."
    }
    $NamedMcClasses = $probe.FullName
}
Write-Step "named Minecraft classes : $NamedMcClasses"

$versionJson = Get-ChildItem (Join-Path $McRoot "versions") -Directory -Filter "1.21.1-NeoForge*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
$classpath = New-Object System.Collections.Generic.List[string]
$classpath.Add($NamedMcClasses)
if ($versionJson) {
    $jsonPath = Join-Path $versionJson.FullName ("{0}.json" -f $versionJson.Name)
    Write-Step "NeoForge version json   : $jsonPath"
    $json = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
    foreach ($lib in $json.libraries) {
        $relative = $null
        if ($lib.downloads -and $lib.downloads.artifact) { $relative = $lib.downloads.artifact.path }
        if (-not $relative) { continue }
        $full = Join-Path $libraries $relative
        if (Test-Path -LiteralPath $full) { $classpath.Add($full) }
    }
} else {
    # The one-off test instance may already be gone: fall back to the shared libraries.
    Write-Step "no 1.21.1 NeoForge instance; using the shared libraries"
    foreach ($rel in @(
        "com\google\code\gson\gson\2.10.1\gson-2.10.1.jar",
        "org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar",
        "net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar",
        "net\neoforged\mergetool\2.0.0\mergetool-2.0.0-api.jar",
        # BlendGuard reads GL_BLEND through LWJGL, so lwjgl-opengl must be on the compile classpath.
        "org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar",
        "org\lwjgl\lwjgl-opengl\3.3.3\lwjgl-opengl-3.3.3.jar"
    )) { $p = Join-Path $libraries $rel; if (Test-Path $p) { $classpath.Add($p) } }
}

# NeoForge itself (the mod jar holding net.neoforged.neoforge.*)
$neoVersion = $null
if ($versionJson) { $neoVersion = $versionJson.Name -replace '^1\.21\.1-NeoForge_', '' }
if (-not $neoVersion -or -not (Test-Path (Join-Path $libraries ("net\neoforged\neoforge\{0}" -f $neoVersion)))) {
    $neoPkg = Get-ChildItem (Join-Path $libraries "net\neoforged\neoforge") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '21.1.*' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if ($neoPkg) { $neoVersion = $neoPkg.Name }
}
$neoDir = Join-Path $libraries ("net\neoforged\neoforge\{0}" -f $neoVersion)
if (Test-Path $neoDir) {
    Write-Step "NeoForge                : $neoVersion"
    Get-ChildItem $neoDir -Filter "*.jar" | ForEach-Object { $classpath.Add($_.FullName) }
}

# FancyModLoader (net.neoforged.fml.common.Mod, the @Mod annotation)
$fml = Get-ChildItem (Join-Path $libraries "net\neoforged\fancymodloader\loader") -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
    Where-Object { $_.Name -like "4.*" } | Select-Object -First 1
if ($fml) {
    Get-ChildItem $fml.FullName -Filter "*.jar" | ForEach-Object { $classpath.Add($_.FullName) }
    Write-Step "FancyModLoader          : $($fml.Name)"
}

$cp = ($classpath | Select-Object -Unique) -join ";"
Write-Step "classpath entries       : $(($classpath | Select-Object -Unique).Count)"

# ---------------------------------------------------------------- compile
$classes = Join-Path $ProjectRoot "build\classes"
$jarOut = Join-Path $ProjectRoot ("build\tooltipfix-{0}.jar" -f $Version)
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Resources first, so the jar picks them up without a second copy step.
Copy-Item (Join-Path $ProjectRoot "src\main\resources\*") $classes -Recurse -Force

$sources = Get-ChildItem (Join-Path $ProjectRoot "src\main\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
Write-Step "compiling $($sources.Count) source files"

$javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
if (-not (Test-Path $javac)) { $javac = "javac" }

& $javac -encoding UTF-8 --release 21 -nowarn -proc:none -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

# ---------------------------------------------------------------- package
if (Test-Path $jarOut) { Remove-Item $jarOut -Force }
$jar = Join-Path $env:JAVA_HOME "bin\jar.exe"
if (-not (Test-Path $jar)) { $jar = "jar" }

& $jar --create --file $jarOut -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }

Write-Step "built $jarOut ($((Get-Item $jarOut).Length) bytes)"
