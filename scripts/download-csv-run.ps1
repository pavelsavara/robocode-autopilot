param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][int]$NumChunks,
    [int]$MaxRetries = 8,
    [int]$AttemptTimeoutSec = 900
)

$ErrorActionPreference = 'Continue'
$baseDir = "output\csv-$RunId"
New-Item -ItemType Directory -Force $baseDir | Out-Null

function Get-DirSize($path) {
    if (-not (Test-Path $path)) { return 0 }
    $files = Get-ChildItem $path -Recurse -File -ErrorAction SilentlyContinue
    if (-not $files) { return 0 }
    return ($files | Measure-Object Length -Sum).Sum
}

for ($i = 0; $i -lt $NumChunks; $i++) {
    $name = "csv-chunk-$i"
    $dest = Join-Path $baseDir $name

    $sz = Get-DirSize $dest
    if ($sz -gt 0) {
        Write-Host ("[{0}] {1} already present ({2:N0} bytes) - skip" -f (Get-Date -Format HH:mm:ss), $name, $sz)
        continue
    }
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue }

    $attempt = 0
    $success = $false
    while ($attempt -lt $MaxRetries -and -not $success) {
        $attempt++
        Write-Host ("[{0}] downloading {1} (attempt {2}/{3}, timeout {4}s) ..." -f (Get-Date -Format HH:mm:ss), $name, $attempt, $MaxRetries, $AttemptTimeoutSec)

        # Launch gh as a child process so we can monitor and kill on stall.
        $absDest = Join-Path (Get-Location).Path $dest
        New-Item -ItemType Directory -Force $absDest | Out-Null
        $proc = Start-Process -FilePath "gh" -ArgumentList @("run","download",$RunId,"--name",$name,"--dir",$absDest) -PassThru -NoNewWindow

        $deadline = (Get-Date).AddSeconds($AttemptTimeoutSec)

        while (-not $proc.HasExited) {
            Start-Sleep -Seconds 10
            $now = Get-Date
            if ($now -gt $deadline) {
                Write-Host ("  [{0}] hard timeout - killing pid {1}" -f (Get-Date -Format HH:mm:ss), $proc.Id)
                try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
                break
            }
            $cur = Get-DirSize $absDest
            Write-Host ("  [{0}] {1:N0} bytes" -f (Get-Date -Format HH:mm:ss), $cur)
        }
        try { $proc.WaitForExit(5000) | Out-Null } catch {}
        $rc = -1
        try { $rc = $proc.ExitCode } catch {}

        $finalSize = Get-DirSize $absDest
        if ($rc -eq 0 -and $finalSize -gt 0) {
            Write-Host ("  OK ({0:N0} bytes)" -f $finalSize)
            $success = $true
        } else {
            Write-Host ("  FAILED rc={0} size={1:N0} - cleaning up" -f $rc, $finalSize)
            Remove-Item $absDest -Recurse -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 5
        }
    }
    if (-not $success) {
        Write-Host ("[{0}] GIVING UP on {1} after {2} attempts" -f (Get-Date -Format HH:mm:ss), $name, $MaxRetries)
    }
}
Write-Host "Done with run $RunId."
