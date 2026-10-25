$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()

$configPath = Join-Path $root 'config\session-metrics-test.conf'
$stdoutPath = Join-Path $root 'session-metrics-test.out'
$stderrPath = Join-Path $root 'session-metrics-test.err'
$cgiPath = Join-Path $root 'www\cgi-bin\chunk_echo_test.py'
$process = $null
$passed = 0
$failed = 0

function Assert-Check([string]$name, [bool]$condition, [string]$details = '') {
    if ($condition) {
        Write-Host "PASS: $name" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "FAIL: $name $details" -ForegroundColor Red
        $script:failed++
    }
}

try {
    $config = @"
server {
    listen $port
    server_name localhost
    default_server
    document_root ./www
    route / root
    route /cgi-bin cgi ./www/cgi-bin
}
"@
    [System.IO.File]::WriteAllText($configPath, $config, [System.Text.Encoding]::UTF8)

    $process = Start-Process -FilePath 'java' -ArgumentList @('-cp', 'bin', 'Main', 'config/session-metrics-test.conf') `
        -PassThru -NoNewWindow -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $baseUrl = "http://127.0.0.1:$port"
    $ready = $false
    for ($i = 0; $i -lt 50; $i++) {
        if ($process.HasExited) { break }
        try {
            $readyResponse = curl.exe -sS --max-time 1 -H 'Host: localhost' "$baseUrl/" 2>$null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Assert-Check 'Server starts with metrics/session configuration' $ready
    if (-not $ready) {
        if (Test-Path $stdoutPath) { Write-Host (Get-Content $stdoutPath -Raw) }
        if (Test-Path $stderrPath) { Write-Host (Get-Content $stderrPath -Raw) }
    }

    if ($ready) {
        $first = curl.exe -sS -i -H 'Host: localhost' "$baseUrl/hello.txt" 2>&1 | Out-String
        $cookieMatch = [regex]::Match($first, '(?im)^Set-Cookie:\s*SESSIONID=([^;\r\n]+); Path=/; HttpOnly')
        Assert-Check 'First HTTP response issues an HttpOnly session cookie' $cookieMatch.Success

        if ($cookieMatch.Success) {
            $sessionId = $cookieMatch.Groups[1].Value
            $second = curl.exe -sS -i -H 'Host: localhost' -H "Cookie: SESSIONID=$sessionId" "$baseUrl/hello.txt" 2>&1 | Out-String
            $sameCookie = [regex]::Match($second, '(?im)^Set-Cookie:\s*SESSIONID=([^;\r\n]+)')
            Assert-Check 'Existing cookie resumes the same server session' ($sameCookie.Success -and $sameCookie.Groups[1].Value -eq $sessionId)
        }

        $metrics = curl.exe -sS -H 'Host: localhost' "$baseUrl/__metrics" 2>&1 | Out-String
        Assert-Check 'Metrics endpoint reports request, connection, and session counters' `
            ($metrics -match 'localserver_requests_total [1-9]' -and
             $metrics -match 'localserver_connections_total [1-9]' -and
             $metrics -match 'localserver_sessions_active [1-9]')

        $cgiScript = @'
import sys
body = sys.stdin.read()
print("Content-Type: text/plain")
print()
print("BODY:" + body)
'@
        [System.IO.File]::WriteAllText($cgiPath, $cgiScript, [System.Text.Encoding]::UTF8)
        $cgiResponse = curl.exe --http1.1 -sS -i -H 'Host: localhost' -H 'Transfer-Encoding: chunked' `
            --data-binary 'chunked-cgi-body' "$baseUrl/cgi-bin/chunk_echo_test.py" 2>&1 | Out-String
        Assert-Check 'Chunked request body reaches CGI stdin' ($cgiResponse -match 'BODY:chunked-cgi-body')
    }
} catch {
    Write-Host "FAIL: Test execution error: $($_.Exception.Message)" -ForegroundColor Red
    $failed++
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit()
    }
    Remove-Item -LiteralPath $configPath, $stdoutPath, $stderrPath, $cgiPath -Force -ErrorAction SilentlyContinue
}

Write-Host "`nSession/Metrics/CGI Summary: $passed passed, $failed failed"
if ($failed -gt 0) { exit 1 }
