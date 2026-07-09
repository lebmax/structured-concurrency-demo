param(
    [string]$Scenario = "all",
    [string]$JdkHome = "C:\Program Files\Axiom\AxiomJDK-26",
    [int]$Release = 26
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$java = Join-Path $JdkHome "bin\java.exe"
$compileScript = Join-Path $PSScriptRoot "compile.ps1"
$buildDir = Join-Path $projectRoot "build\classes"

if (-not (Test-Path $java)) {
    throw "java not found: $java"
}

& $compileScript -JdkHome $JdkHome -Release $Release
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $java --enable-preview -cp $buildDir dev.axiom.scdemo.StructuredConcurrencyDemo $Scenario
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

