param(
    [string]$RobocodeDir = "c:\robocode",
    [int]$BattlesPerOpponent = 5,
    [int]$Rounds = 35,
    [int]$OpponentLimit = 0,
    [string[]]$Opponents = @(),
    [switch]$SkipBuild,
    [switch]$SkipBattles,
    [switch]$SkipPipeline,
    [switch]$SkipRetrain
)

$ErrorActionPreference = 'Stop'
$projectRoot = Get-Location
$outputDir       = Join-Path $projectRoot "output\local"
$recordingsDir   = Join-Path $outputDir "recordings"
$resultsDir      = Join-Path $outputDir "results"
$csvDir          = Join-Path $outputDir "csv"
$robotsDir       = Join-Path $RobocodeDir "robots"
$ourBotPrefix    = "cz.zamboch.Autopilot"
$ourBotClass     = "cz.zamboch.Autopilot"
$runBattleScript = Join-Path $projectRoot "rumble\scripts\run-battle.mjs"

function Log($msg) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) -ForegroundColor Cyan }
function LogWarn($msg) { Write-Host ("[{0}] WARN: {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) -ForegroundColor Yellow }
function LogError($msg) { Write-Host ("[{0}] ERROR: {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) -ForegroundColor Red }

# --- 10a. Build and deploy robot JAR ---
if (-not $SkipBuild) {
    Log "STEP 10a: Building robot JAR..."
    & .\gradlew.bat :robot:jar 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { LogError "Gradle build failed"; exit 1 }

    $jarFile = Get-ChildItem "robot\build\libs" -Filter "$ourBotPrefix-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jarFile) { LogError "No robot JAR found"; exit 1 }
    Log ("  Built: " + $jarFile.Name)

    # Validate JAR
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarFile.FullName)
        $entryCount = $zip.Entries.Count; $zip.Dispose()
        if ($entryCount -lt 5) { LogError "JAR appears corrupt"; exit 1 }
        Log ("  JAR valid, " + $entryCount + " entries")
    } catch { LogError "JAR validation failed: $_"; exit 1 }

    Copy-Item $jarFile.FullName (Join-Path $robotsDir $jarFile.Name) -Force
    Log ("  Deployed to " + $robotsDir)
} else {
    Log "STEP 10a: Skipped"
}

# --- 10b. Run battles ---
if (-not $SkipBattles) {
    Log "STEP 10b: Running battles..."
    New-Item -ItemType Directory -Force $recordingsDir | Out-Null
    New-Item -ItemType Directory -Force $resultsDir | Out-Null

    # Delete stale robot database to force Robocode to rescan JARs
    $robotDb = Join-Path $robotsDir "robot.database"
    if (Test-Path $robotDb) { Remove-Item $robotDb -Force; Log "  Deleted stale robot.database" }

    # Validate JARs - skip corrupt ones
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $validJarNames = @{}
    $corruptCount = 0
    foreach ($j in (Get-ChildItem $robotsDir -Filter "*.jar")) {
        try {
            $z = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
            $null = $z.Entries.Count; $z.Dispose()
            $validJarNames[$j.Name] = $true
        } catch { $corruptCount++ }
    }
    Log ("  Valid JARs: " + $validJarNames.Count + ", Corrupt: " + $corruptCount + " (skipped)")

    # Enumerate valid opponent JARs only
    if ($Opponents.Count -gt 0) {
        # Use specified opponent class names, match to valid JARs
        $opponentJars = @()
        foreach ($opp in $Opponents) {
            $matched = Get-ChildItem $robotsDir -Filter "*.jar" |
                Where-Object { $_.Name -notlike "$ourBotPrefix*" -and $validJarNames.ContainsKey($_.Name) -and $_.BaseName -like "$opp*" }
            if ($matched) { $opponentJars += $matched | Select-Object -First 1 }
            else { LogWarn "No JAR found matching opponent '$opp'" }
        }
    } else {
        $opponentJars = Get-ChildItem $robotsDir -Filter "*.jar" |
            Where-Object { $_.Name -notlike "$ourBotPrefix*" -and $validJarNames.ContainsKey($_.Name) } |
            Sort-Object Name
    }

    if ($opponentJars.Count -eq 0) { LogError "No valid opponent JARs found"; exit 1 }
    if ($OpponentLimit -gt 0 -and $opponentJars.Count -gt $OpponentLimit) {
        $opponentJars = $opponentJars | Select-Object -First $OpponentLimit
        Log ("  Limited to " + $opponentJars.Count + " opponents (of available)")
    }
    Log ("  " + $opponentJars.Count + " valid opponents")

    $opponentNum = 0
    $skippedOpponents = 0
    $allResults = @()

    foreach ($jar in $opponentJars) {
        $baseName = $jar.BaseName
        $idx = $baseName.IndexOf('_')
        if ($idx -ge 0) { $opponentClass = $baseName.Substring(0, $idx) }
        else { $opponentClass = $baseName }

        $opponentNum++
        $opponentFailed = $false

        for ($b = 1; $b -le $BattlesPerOpponent; $b++) {
            if ($opponentFailed) { continue }

            $pct = [math]::Round(100 * (($opponentNum - 1) * $BattlesPerOpponent + $b) / ($opponentJars.Count * $BattlesPerOpponent))
            Log ("  [{0}/{1}]-{6}% {2} vs {3} ({4}/{5})" -f $opponentNum, $opponentJars.Count, $ourBotClass, $opponentClass, $b, $BattlesPerOpponent, $pct)

            try {
                $result = node $runBattleScript --robocode-dir $RobocodeDir --bot-a $ourBotClass --bot-b $opponentClass --rounds $Rounds --record-dir $recordingsDir 2>&1
                $resultText = $result -join "`n"

                try {
                    $json = $resultText | ConvertFrom-Json
                    if ($json.error) {
                        if ($json.stderr -and $json.stderr -match "Can.t find") {
                            LogWarn ("    Opponent '" + $opponentClass + "' not found - skipping")
                            $opponentFailed = $true
                            $skippedOpponents++
                        } else {
                            LogWarn ("    Battle error: " + $json.message)
                        }
                    } else {
                        $scoreA = $json.bot_a.score_pct
                        $scoreB = $json.bot_b.score_pct
                        $elapsed = [math]::Round($json.elapsed_ms / 1000, 1)
                        Log ("    {0} {1}% vs {2} {3}% - {4}s" -f $json.bot_a.name, $scoreA, $json.bot_b.name, $scoreB, $elapsed)

                        $resultFile = Join-Path $resultsDir ("battle_{0}_{1}.json" -f $opponentNum, $b)
                        $resultText | Set-Content $resultFile -Encoding utf8
                        $allResults += $json
                    }
                } catch {
                    LogWarn ("    Could not parse result")
                }
            } catch {
                LogWarn ("    Battle exception: " + $_)
            }
        }
    }

    $summaryFile = Join-Path $resultsDir "summary.json"
    $allResults | ConvertTo-Json -Depth 10 | Set-Content $summaryFile -Encoding utf8
    $successCount = ($allResults | Where-Object { -not $_.error }).Count
    Log ("  Done: " + $successCount + " successful battles, " + $skippedOpponents + " opponents skipped")
    $brFiles = Get-ChildItem $recordingsDir -Filter "*.br" -ErrorAction SilentlyContinue
    Log ("  Recordings: " + $brFiles.Count + " .br files")
} else {
    Log "STEP 10b: Skipped"
}

# --- 10c. Process recordings into CSVs ---
if (-not $SkipPipeline) {
    Log "STEP 10c: Processing recordings into CSVs..."
    & .\gradlew.bat :pipeline:installDist 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { LogError "Pipeline build failed"; exit 1 }

    $brFiles = Get-ChildItem $recordingsDir -Filter "*.br" -ErrorAction SilentlyContinue
    if (-not $brFiles -or $brFiles.Count -eq 0) { LogError "No .br files found"; exit 1 }
    Log ("  Processing " + $brFiles.Count + " recordings...")

    $pipelineBin = Join-Path $projectRoot "pipeline\build\install\pipeline\bin\pipeline.bat"
    New-Item -ItemType Directory -Force $csvDir | Out-Null
    & $pipelineBin --input $recordingsDir --output $csvDir 2>&1 | ForEach-Object { Write-Host "  $_" }

    $csvFiles = Get-ChildItem $csvDir -Filter "*.csv" -Recurse -ErrorAction SilentlyContinue
    Log ("  Generated " + $csvFiles.Count + " CSV files")
} else {
    Log "STEP 10c: Skipped"
}

# --- Done ---
Log 'Pipeline complete!'
Log ('  Recordings: ' + $recordingsDir)
Log ('  Results:    ' + $resultsDir)
Log ('  CSVs:       ' + $csvDir)

# --- 10d. Retrain models and regenerate Java code ---
if (-not $SkipRetrain) {
    Log "STEP 10d: Retraining GBM models on local battle data..."
    $pythonExe = Join-Path $projectRoot "intuition\.venv\Scripts\python.exe"
    if (-not (Test-Path $pythonExe)) {
        LogError "Python venv not found at $pythonExe"
        exit 1
    }

    # Check CSV files exist
    $csvFiles = Get-ChildItem $csvDir -Filter "ticks.csv" -Recurse -ErrorAction SilentlyContinue
    if (-not $csvFiles -or $csvFiles.Count -eq 0) {
        LogError "No ticks.csv files found in $csvDir - cannot retrain"
        exit 1
    }
    Log ("  Found " + $csvFiles.Count + " ticks.csv files for training")

    # Train all 3 models pointing at local CSV output
    $trainScript = Join-Path $projectRoot "intuition\train_distill.py"
    Log "  Training fire_power, movement, fire_timing models..."
    Push-Location (Join-Path $projectRoot "intuition")
    try {
        & $pythonExe $trainScript --task all --roots $csvDir 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { LogError "Model training failed"; exit 1 }
        Log "  Models trained successfully"
    } finally {
        Pop-Location
    }

    # Export models to Java Base64 classes
    $exportScript = Join-Path $projectRoot "intuition\export_gbm_java.py"
    Log "  Exporting models to Java..."
    Push-Location (Join-Path $projectRoot "intuition")
    try {
        & $pythonExe $exportScript --all 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { LogError "Model export failed"; exit 1 }
        Log "  Java model classes regenerated"
    } finally {
        Pop-Location
    }

    # Export data file (VCS priors) as embedded Java fallback
    $dataFilePath = Join-Path $RobocodeDir "robots\.data\cz\zamboch\Autopilot.data\autopilot.dat"
    if (Test-Path $dataFilePath) {
        $exportDataScript = Join-Path $projectRoot "intuition\export_data_java.py"
        Log "  Exporting data file as embedded fallback..."
        & $pythonExe $exportDataScript $dataFilePath 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { LogWarn "Data file export failed (non-fatal)" }
    } else {
        LogWarn "  No data file found at $dataFilePath (will be created after first battle)"
    }

    # Rebuild robot JAR with updated models
    Log "  Rebuilding robot JAR with new models..."
    & .\gradlew.bat :robot:jar 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { LogError "Rebuild failed"; exit 1 }

    $jarFile = Get-ChildItem "robot\build\libs" -Filter "$ourBotPrefix-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Copy-Item $jarFile.FullName (Join-Path $robotsDir $jarFile.Name) -Force
    Log ("  Rebuilt and deployed: " + $jarFile.Name)
} else {
    Log "STEP 10d: Skipped"
}

Log 'Full pipeline complete!'
