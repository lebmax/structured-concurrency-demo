param(
    [string]$JdkHome = "C:\Program Files\Axiom\AxiomJDK-26",
    [int]$Release = 26
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$javac = Join-Path $JdkHome "bin\javac.exe"
$sourceRoot = Join-Path $projectRoot "src\main\java"
$buildDir = Join-Path $projectRoot "build\classes"

if (-not (Test-Path $javac)) {
    throw "javac not found: $javac"
}

$sources = Get-ChildItem -Path $sourceRoot -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
if (-not $sources) {
    throw "No Java sources found under $sourceRoot"
}

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

& $javac --enable-preview --release $Release -encoding UTF-8 -d $buildDir $sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Compiled to $buildDir"

