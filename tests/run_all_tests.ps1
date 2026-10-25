param(
    [switch]$SkipAudit,
    [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configPath = Join-Path $repoRoot 'config/server.conf'
$configSnapshot = [System.IO.File]::ReadAllBytes($configPath)
$failures = [System.Collections.Generic.List[string]]::new()
$powershellExe = (Get-Command powershell.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $powershellExe) {
    $powershellExe = (Get-Command pwsh.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    Write-Host "`n===== $Name =====" -ForegroundColor Cyan
    $global:LASTEXITCODE = 0
    $output = @(& $Executable @Arguments 2>&1)
    $commandExitCode = $LASTEXITCODE
    foreach ($line in $output) {
        Write-Host $line
    }

    $failureLines = @($output | Where-Object {
        $rendered = $_.ToString()
        $rendered -match '^\s*(\[FAIL\]|FAIL:)' -or
        $rendered -match '\b[1-9][0-9]*\s+failed\b'
    })
    if ($commandExitCode -ne 0 -or $failureLines.Count -gt 0) {
        $failures.Add($Name)
        Write-Host "FAILED: $Name (exit code $commandExitCode)" -ForegroundColor Red
        return $false
    } else {
        Write-Host "PASSED: $Name" -ForegroundColor Green
        return $true
    }
}

function ConvertTo-WslPath {
    param([Parameter(Mandatory = $true)][string]$WindowsPath)

    $converted = & wsl.exe --exec wslpath -a $WindowsPath 2>&1
    if ($LASTEXITCODE -ne 0 -or -not $converted) {
        throw "Could not convert path for WSL: $WindowsPath"
    }
    return ($converted | Select-Object -Last 1).ToString().Trim()
}

Push-Location $repoRoot
try {
    if (-not $SkipCompile) {
        $javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src') -Filter '*.java' -Recurse |
            ForEach-Object { $_.FullName })
        if ($javaFiles.Count -eq 0) {
            throw 'No Java source files were found under src/.'
        }
        $compileArguments = @(
            '-d', (Join-Path $repoRoot 'bin'),
            '-sourcepath', (Join-Path $repoRoot 'src')
        ) + $javaFiles
        if (-not (Invoke-CheckedCommand -Name 'Compile Java project' -Executable 'javac' -Arguments $compileArguments)) {
            throw 'Compilation failed; test suites were not run.'
        }
    }

    if (-not $SkipAudit) {
        $python = Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $python) {
            $python = Get-Command python -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if (-not $python) {
            $python = Get-Command python3 -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if ($python) {
            $null = Invoke-CheckedCommand -Name 'Python audit runner' -Executable $python.Source -Arguments @('tests/audit_runner.py')
        } else {
            $failures.Add('Python audit runner (Python executable not found)')
            Write-Host 'FAILED: Python audit runner; install Python or add python to PATH.' -ForegroundColor Red
        }
    }

    if (-not $powershellExe) {
        throw 'Could not find powershell.exe or pwsh.exe to run the PowerShell suites.'
    }

    $powerShellSuites = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tests') -Filter '*.ps1' -File -Recurse |
        Where-Object { $_.FullName -ne $PSCommandPath } |
        Sort-Object Name)
    foreach ($suite in $powerShellSuites) {
        $null = Invoke-CheckedCommand -Name $suite.Name -Executable $powershellExe -Arguments @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $suite.FullName
        )
    }

    $shellSuites = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tests') -Filter '*.sh' -File -Recurse |
        Sort-Object Name)
    if ($shellSuites.Count -eq 0) {
        Write-Host "`nNo .sh test scripts found; shell-test step is empty." -ForegroundColor DarkGray
    } else {
        $gitBash = @(
            (Join-Path $env:ProgramFiles 'Git/bin/bash.exe'),
            (Join-Path ${env:ProgramFiles(x86)} 'Git/bin/bash.exe')
        ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1

        if ($gitBash) {
            foreach ($suite in $shellSuites) {
                $null = Invoke-CheckedCommand -Name $suite.Name -Executable $gitBash -Arguments @('--noprofile', '--norc', $suite.FullName)
            }
        } elseif (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
            $wslRoot = ConvertTo-WslPath -WindowsPath $repoRoot
            foreach ($suite in $shellSuites) {
                $wslSuite = ConvertTo-WslPath -WindowsPath $suite.FullName
                $null = Invoke-CheckedCommand -Name $suite.Name -Executable 'wsl.exe' -Arguments @(
                    '--cd', $wslRoot, '--exec', 'bash', $wslSuite
                )
            }
        } else {
            $failures.Add('Shell test scripts (Git Bash or WSL not found)')
            Write-Host 'FAILED: .sh tests exist, but neither Git Bash nor WSL is available.' -ForegroundColor Red
        }
    }
} catch {
    $failures.Add("Runner error: $($_.Exception.Message)")
    Write-Host "`nRUNNER ERROR: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    [System.IO.File]::WriteAllBytes($configPath, $configSnapshot)
    Pop-Location
}

Write-Host "`n===== Overall test result =====" -ForegroundColor Cyan
if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) suite(s) failed:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}

Write-Host 'All discovered test suites passed.' -ForegroundColor Green
exit 0
