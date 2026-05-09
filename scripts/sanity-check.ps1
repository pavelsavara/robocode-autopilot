<#
.SYNOPSIS
    Automated sanity check script for robocode-autopilot.
    Runs 6 mandatory checks + 3 bonus ML checks on pipeline CSV output.

.DESCRIPTION
    Reads data from output/local/csv/ and output/local/results/summary.json,
    runs all Phase 4a mandatory sanity checks, and prints a pass/fail summary.

    Exit code 0 = all mandatory checks pass.
    Exit code 1 = at least one mandatory check failed.
    Exit code 2 = data not found or script error.

.PARAMETER DataDir
    Path to the data directory (default: output/local/ relative to repo root).
    Must contain csv/ subdirectory with battle data.

.EXAMPLE
    .\scripts\sanity-check.ps1
    .\scripts\sanity-check.ps1 -DataDir "D:\robocode-autopilot\output\local"
#>

param(
    [string]$DataDir = "",
    [string]$PythonExe = ""
)

$ErrorActionPreference = "Stop"

# Resolve repo root (script is in scripts/)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$repoRoot = Split-Path -Parent $scriptDir

# Resolve data directory
if ($DataDir -eq "") {
    $DataDir = Join-Path $repoRoot "output\local"
}

if (-not (Test-Path $DataDir)) {
    Write-Error "Data directory not found: $DataDir"
    exit 2
}

# Find Python executable
if ($PythonExe -ne "" -and (Test-Path $PythonExe)) {
    $venvPython = $PythonExe
} else {
    # Try venv relative to repo root
    $venvPython = Join-Path $repoRoot "intuition\.venv\Scripts\python.exe"
    if (-not (Test-Path $venvPython)) {
        # Fallback: try system python
        $venvPython = "python"
        Write-Warning "Venv not found at intuition/.venv/, falling back to system python"
    }
}

# Run the Python sanity check script
$pythonScript = Join-Path $scriptDir "sanity_check.py"
if (-not (Test-Path $pythonScript)) {
    Write-Error "Python script not found: $pythonScript"
    exit 2
}

& $venvPython $pythonScript --data-dir $DataDir
$exitCode = $LASTEXITCODE

exit $exitCode
