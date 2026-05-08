<#
.SYNOPSIS
    Local pipeline: build robot → battle opponents → record → CSV → analyze.

.DESCRIPTION
    Implements Phase 10 of the robocode-autopilot project plan.
    Steps:
      10a. Build robot JAR and deploy to c:\robocode\robots\
      10b. Run battles against each opponent with recording
      10c. Process recordings into CSVs via the pipeline
      10d. (Manual) Run retrospective notebooks in intuition/retrospective/

.PARAMETER RobocodeDir
    Path to the Robocode installation. Default: c:\robocode

.PARAMETER BattlesPerOpponent
    Number of battles to run per opponent. Default: 5

.PARAMETER Rounds
    Number of rounds per battle. Default: 35

.PARAMETER SkipBuild
    Skip the Gradle build step (use existing JAR).

.PARAMETER SkipBattles
    Skip battle execution (use existing recordings).

.PARAMETER SkipPipeline
    Skip CSV pipeline processing (use existing CSVs).
#>
param(
    [string]$RobocodeDir = "c:\robocode",
    [int]$BattlesPerOpponent = 5,
    [int]$Rounds = 35,
    [switch]$SkipBuild,
    [switch]$SkipBattles,
    [switch]$SkipPipeline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# If invoked from the project root, adjust
if (-not (Test-Path (Join-Path $projectRoot "gradlew.bat"))) {
    $projectRoot = Get-Location
}

$outputDir       = Join-Path $projectRoot "output\local"
$recordingsDir   = Join-Path $outputDir "recordings"
$resultsDir      = Join-Path $outputDir "results"
$csvDir          = Join-Path $outputDir "csv"
$robotsDir       = Join-Path $RobocodeDir "robots"
$ourBotPrefix    = "cz.zamboch.Autopilot"
$ourBotClass     = "cz.zamboch.Autopilot"
$runBattleScript = Join-Path $projectRoot "rumble\scripts\run-battle.mjs"

function Log($msg) {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Cyan
}

function LogWarn($msg) {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] WARNING: $msg" -ForegroundColor Yellow
}

function LogError($msg) {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ERROR: $msg" -ForegroundColor Red
}

# ────────────────────────────────────────────────────────────────────
# 10a. Build & deploy robot JAR
# ────────────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Log "STEP 10a: Building robot JAR..."

    Push-Location $projectRoot
    try {
        & .\gradlew.bat :robot:jar 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) {
            LogError "Gradle build failed with exit code $LASTEXITCODE"
            exit 1
        }
    } finally {
        Pop-Location
    }

    # Find the built JAR
    $jarDir = Join-Path $projectRoot "robot\build\libs"
    $jarFile = Get-ChildItem $jarDir -Filter "$ourBotPrefix-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $jarFile) {
        LogError "No robot JAR found in $jarDir"
        exit 1
    }

    Log "  Built: $($jarFile.Name)"

    # Validate JAR integrity
    Log "  Validating JAR integrity..."
    $validateProc = Start-Process -FilePath "java" -ArgumentList @("-jar", $jarFile.FullName, "--version") `
        -Wait -PassThru -NoNewWindow -RedirectStandardOutput "NUL" -RedirectStandardError "NUL" 2>$null
    # Simple check: just verify it's a valid zip
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarFile.FullName)
        $entryCount = $zip.Entries.Count
        $zip.Dispose()
        if ($entryCount -lt 5) {
            LogError "JAR appears corrupt (only $entryCount entries)"
            exit 1
        }
        Log "  JAR valid ($entryCount entries)"
    } catch {
        LogError "JAR validation failed: $_"
        exit 1
    }

    # Copy to Robocode robots directory
    $destJar = Join-Path $robotsDir $jarFile.Name
    Copy-Item $jarFile.FullName $destJar -Force
    Log "  Deployed to: $destJar"
} else {
    Log "STEP 10a: Skipped (--SkipBuild)"
}

# ────────────────────────────────────────────────────────────────────
# 10b. Run battles with recording
# ────────────────────────────────────────────────────────────────────
if (-not $SkipBattles) {
    Log "STEP 10b: Running battles..."

    # Create output directories
    New-Item -ItemType Directory -Force $recordingsDir | Out-Null
    New-Item -ItemType Directory -Force $resultsDir | Out-Null

    # Enumerate opponent JARs
    $opponentJars = Get-ChildItem $robotsDir -Filter "*.jar" |
        Where-Object { $_.Name -notlike "$ourBotPrefix*" } |
        Sort-Object Name

    if ($opponentJars.Count -eq 0) {
        LogError "No opponent JARs found in $robotsDir"
        exit 1
    }

    Log "  Found $($opponentJars.Count) opponent(s)"

    $totalBattles = $opponentJars.Count * $BattlesPerOpponent
    $battleNum = 0
    $allResults = @()

    foreach ($jar in $opponentJars) {
        # Extract bot class name from JAR filename
        # JAR naming convention: <fully.qualified.ClassName>_<version>.jar
        $baseName = $jar.BaseName  # e.g. "jk.mega.DrussGT_3.1.6"
        # Replace the FIRST underscore with a space to get "class version" format
        # that Robocode's selectedRobots expects (e.g. "jk.mega.DrussGT 3.1.7")
        $firstUnderscoreIdx = $baseName.IndexOf('_')
        if ($firstUnderscoreIdx -ge 0) {
            $opponentClass = $baseName.Substring(0, $firstUnderscoreIdx)
        } else {
            $opponentClass = $baseName
        }

        for ($b = 1; $b -le $BattlesPerOpponent; $b++) {
            $battleNum++
            $pct = [math]::Round(100 * $battleNum / $totalBattles)
            Log "  Battle $battleNum/$totalBattles ($pct%): $ourBotClass vs $opponentClass (round $b/$BattlesPerOpponent)"

            $battleArgs = @(
                $runBattleScript,
                "--robocode-dir", $RobocodeDir,
                "--bot-a", $ourBotClass,
                "--bot-b", $opponentClass,
                "--rounds", $Rounds.ToString(),
                "--record-dir", $recordingsDir
            )

            try {
                $result = node @battleArgs 2>&1
                $resultText = $result -join "`n"

                # Try to parse as JSON
                try {
                    $json = $resultText | ConvertFrom-Json
                    if ($json.error) {
                        LogWarn "    Battle error: $($json.message)"
                    } else {
                        $scoreA = $json.bot_a.score_pct
                        $scoreB = $json.bot_b.score_pct
                        Log "    Result: $($json.bot_a.name) $scoreA% vs $($json.bot_b.name) $scoreB% (${($json.elapsed_ms / 1000)}s)"
                    }

                    # Save result JSON
                    $resultFile = Join-Path $resultsDir "battle_${battleNum}.json"
                    $resultText | Set-Content $resultFile -Encoding utf8
                    $allResults += $json
                } catch {
                    LogWarn "    Could not parse result: $($resultText.Substring(0, [Math]::Min(200, $resultText.Length)))"
                }
            } catch {
                LogWarn "    Battle failed: $_"
            }
        }
    }

    # Save summary
    $summaryFile = Join-Path $resultsDir "summary.json"
    $allResults | ConvertTo-Json -Depth 10 | Set-Content $summaryFile -Encoding utf8
    Log "  Saved $($allResults.Count) battle results to $summaryFile"

    # Count recordings
    $brFiles = Get-ChildItem $recordingsDir -Filter "*.br" -ErrorAction SilentlyContinue
    Log "  Generated $($brFiles.Count) recording files"
} else {
    Log "STEP 10b: Skipped (--SkipBattles)"
}

# ────────────────────────────────────────────────────────────────────
# 10c. Process recordings into CSVs
# ────────────────────────────────────────────────────────────────────
if (-not $SkipPipeline) {
    Log "STEP 10c: Processing recordings into CSVs..."

    # Build pipeline
    Push-Location $projectRoot
    try {
        & .\gradlew.bat :pipeline:installDist 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) {
            LogError "Pipeline build failed with exit code $LASTEXITCODE"
            exit 1
        }
    } finally {
        Pop-Location
    }

    # Check for recordings
    $brFiles = Get-ChildItem $recordingsDir -Filter "*.br" -ErrorAction SilentlyContinue
    if (-not $brFiles -or $brFiles.Count -eq 0) {
        LogError "No .br recording files found in $recordingsDir"
        exit 1
    }

    Log "  Processing $($brFiles.Count) recordings..."

    # Determine pipeline executable
    $pipelineBin = Join-Path $projectRoot "pipeline\build\install\pipeline\bin\pipeline.bat"
    if (-not (Test-Path $pipelineBin)) {
        $pipelineBin = Join-Path $projectRoot "pipeline\build\install\pipeline\bin\pipeline"
    }

    New-Item -ItemType Directory -Force $csvDir | Out-Null

    & $pipelineBin --input $recordingsDir --output $csvDir 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -eq 2) {
        LogError "Pipeline processing failed - zero recordings processed successfully"
        exit 1
    } elseif ($LASTEXITCODE -ne 0) {
        LogWarn "Pipeline finished with non-zero exit code $LASTEXITCODE (some recordings may have failed)"
    }

    # Count output
    $csvFiles = Get-ChildItem $csvDir -Filter "*.csv" -Recurse -ErrorAction SilentlyContinue
    Log "  Generated $($csvFiles.Count) CSV files"
} else {
    Log "STEP 10c: Skipped (--SkipPipeline)"
}

# ────────────────────────────────────────────────────────────────────
# Done
# ────────────────────────────────────────────────────────────────────
Log "Local pipeline complete!"
Log "  Recordings: $recordingsDir"
Log "  Results:    $resultsDir"
Log "  CSVs:       $csvDir"
Log ""
Log "Next: run retrospective notebooks in intuition/retrospective/"
Log "  cd intuition"
Log "  .venv\Scripts\python.exe -m jupyter nbconvert --to notebook --execute retrospective\R01_win_loss_rates.ipynb --inplace --ExecutePreprocessor.timeout=600 --allow-errors"
