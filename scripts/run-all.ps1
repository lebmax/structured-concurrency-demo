param(
    [string]$JdkHome = "C:\Program Files\Axiom\AxiomJDK-26",
    [int]$Release = 26
)

$ErrorActionPreference = "Stop"

$runScript = Join-Path $PSScriptRoot "run.ps1"

& $runScript -Scenario all -JdkHome $JdkHome -Release $Release
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

