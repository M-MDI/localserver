# Multi-Server Configuration Tests for LocalServer
$ErrorActionPreference = "Stop"

Write-Host "=== Multi-Server Configuration Tests ===" -ForegroundColor Cyan

function Stop-ServerProcess($proc) {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
    }
}

$passCount = 0
$failCount = 0

# Test 1: Single-server backward compatibility
Write-Host "`nTest 1: Single-server backward compatibility" -ForegroundColor Yellow
$proc = $null
try {
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/server.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = Invoke-WebRequest -Uri "http://localhost:65432/" -UseBasicParsing -TimeoutSec 5
    if ($response.StatusCode -eq 200) {
        Write-Host "  PASS: Single-server mode works on port 65432" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Single-server mode failed (status: $($response.StatusCode))" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 1 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
}

# Test 2: Multiple servers with different ports
Write-Host "`nTest 2: Multiple servers with different ports" -ForegroundColor Yellow
$proc = $null
try {
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/multi-port-test.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response1 = Invoke-WebRequest -Uri "http://localhost:8080/" -UseBasicParsing -TimeoutSec 5
    $response2 = Invoke-WebRequest -Uri "http://localhost:8081/" -UseBasicParsing -TimeoutSec 5

    if ($response1.Content -match "Server 1 content" -and $response2.Content -match "Server 2 content") {
        Write-Host "  PASS: Server 1 (port 8080) and Server 2 (port 8081) serve correct document roots" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Content mismatch between ports" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 2 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
}

# Test 3: Multiple servers sharing a port with Host header selection
Write-Host "`nTest 3: Multiple servers sharing a port with Host header selection" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8082
    name server1
    server_name example.com
    document_root ./www/server1
    default_server
}

server {
    listen 8082
    name server2
    server_name test.com
    document_root ./www/server2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_shared_port.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_shared_port.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response1 = Invoke-WebRequest -Uri "http://localhost:8082/" -Headers @{"Host"="example.com"} -UseBasicParsing -TimeoutSec 5
    $response2 = Invoke-WebRequest -Uri "http://localhost:8082/" -Headers @{"Host"="test.com"} -UseBasicParsing -TimeoutSec 5

    if ($response1.Content -match "Server 1 content" -and $response2.Content -match "Server 2 content") {
        Write-Host "  PASS: Host header 'example.com' selects server1 and 'test.com' selects server2" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Host header selection failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 3 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_shared_port.conf" -Force -ErrorAction SilentlyContinue
}

# Test 4: Default server fallback for unknown Host
Write-Host "`nTest 4: Default server fallback for unknown Host" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8083
    name server1
    server_name example.com
    document_root ./www/server1
    default_server
}

server {
    listen 8083
    name server2
    server_name test.com
    document_root ./www/server2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_default_fallback.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_default_fallback.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = Invoke-WebRequest -Uri "http://localhost:8083/" -Headers @{"Host"="unknown.com"} -UseBasicParsing -TimeoutSec 5
    if ($response.Content -match "Server 1 content") {
        Write-Host "  PASS: Unknown Host falls back to configured default_server" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Default server fallback failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 4 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_default_fallback.conf" -Force -ErrorAction SilentlyContinue
}

# Test 5: Missing explicit default_server (deterministic fallback to first)
Write-Host "`nTest 5: Missing explicit default_server (deterministic fallback)" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8084
    name server1
    server_name example.com
    document_root ./www/server1
}

server {
    listen 8084
    name server2
    server_name test.com
    document_root ./www/server2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_implicit_default.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_implicit_default.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = Invoke-WebRequest -Uri "http://localhost:8084/" -Headers @{"Host"="nomatch.com"} -UseBasicParsing -TimeoutSec 5
    if ($response.Content -match "Server 1 content") {
        Write-Host "  PASS: Without explicit default_server, deterministically falls back to first server" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Implicit default server fallback failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 5 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_implicit_default.conf" -Force -ErrorAction SilentlyContinue
}

# Test 6: Duplicate default_server should fail
Write-Host "`nTest 6: Duplicate default_server on same port should fail validation" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8085
    name server1
    document_root ./www/server1
    default_server
}

server {
    listen 8085
    name server2
    document_root ./www/server2
    default_server
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_dup_default.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_dup_default.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $errorContent = if (Test-Path "test_error.txt") { Get-Content "test_error.txt" -Raw } else { "" }
    if ($errorContent -match "Multiple default servers") {
        Write-Host "  PASS: Duplicate default_server rejected with clear error: Multiple default servers" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Duplicate default_server not rejected. Error content: $errorContent" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 6 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_dup_default.conf" -Force -ErrorAction SilentlyContinue
}

# Test 7: Existing error pages and body limits remain specific to the selected server
Write-Host "`nTest 7: Server-specific error pages and body limits" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8086
    name server1
    server_name s1.local
    document_root ./www/server1
    max_body_size 500
    error_page 413 ./errors/413_server1.html
    default_server
}

server {
    listen 8086
    name server2
    server_name s2.local
    document_root ./www/server2
    max_body_size 5000
    error_page 413 ./errors/413_server2.html
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_limits.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_limits.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # Prepare bodies
    $body1k = "A" * 1000
    $body8k = "B" * 8000
    Set-Content -Path "test_body_1k.txt" -Value $body1k -NoNewline
    Set-Content -Path "test_body_8k.txt" -Value $body8k -NoNewline

    # Send 1KB POST to server1 (limit 500 bytes) -> should fail with 413 and server1 error page
    $resp1 = curl.exe -s -i -X POST -H "Host: s1.local" -H "Content-Type: text/plain" --data-binary "@test_body_1k.txt" http://localhost:8086/upload 2>&1
    $resp1Str = [string]$resp1

    # Send 1KB POST to server2 (limit 5000 bytes) -> should not trigger 413
    $resp2 = curl.exe -s -i -X POST -H "Host: s2.local" -H "Content-Type: text/plain" --data-binary "@test_body_1k.txt" http://localhost:8086/upload 2>&1
    $resp2Str = [string]$resp2

    # Send 8KB POST to server2 (limit 5000 bytes) -> should trigger 413 with server2 error page
    $resp3 = curl.exe -s -i -X POST -H "Host: s2.local" -H "Content-Type: text/plain" --data-binary "@test_body_8k.txt" http://localhost:8086/upload 2>&1
    $resp3Str = [string]$resp3

    $s1_413_ok = ($resp1Str -match "413 Payload Too Large" -and $resp1Str -match "Server 1 Custom 413")
    $s2_under_limit = ($resp2Str -notmatch "413 Payload Too Large")
    $s2_413_ok = ($resp3Str -match "413 Payload Too Large" -and $resp3Str -match "Server 2 Custom 413")

    if ($s1_413_ok -and $s2_under_limit -and $s2_413_ok) {
        Write-Host "  PASS: Server-specific body limits and error pages enforced correctly" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Server-specific limits/error pages check failed." -ForegroundColor Red
        Write-Host "    Server1 413 match: $s1_413_ok" -ForegroundColor Red
        Write-Host "    Server2 under limit: $s2_under_limit" -ForegroundColor Red
        Write-Host "    Server2 413 match: $s2_413_ok" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 7 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_limits.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "test_body_1k.txt" -Force -ErrorAction SilentlyContinue
    Remove-Item "test_body_8k.txt" -Force -ErrorAction SilentlyContinue
}

# Test 8: Invalid configurations fail clearly
Write-Host "`nTest 8: Invalid configurations fail clearly" -ForegroundColor Yellow
$proc = $null
try {
    # 8a: Conflicting server_name on the same port
    $configContent = @"
server {
    listen 8087
    name server1
    server_name conflict.local
    document_root ./www/server1
}
server {
    listen 8087
    name server2
    server_name conflict.local
    document_root ./www/server2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_conflict.conf", $configContent, [System.Text.Encoding]::UTF8)
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_conflict.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2
    $err8a = if (Test-Path "test_error.txt") { (Get-Content "test_error.txt" -Raw) } else { "" }
    Stop-ServerProcess $proc
    Remove-Item "config\test_conflict.conf" -Force -ErrorAction SilentlyContinue

    # 8b: Missing listen directive
    $configContent = @"
server {
    name server1
    document_root ./www/server1
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_missing_listen.conf", $configContent, [System.Text.Encoding]::UTF8)
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_missing_listen.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2
    $err8b = if (Test-Path "test_error.txt") { (Get-Content "test_error.txt" -Raw) } else { "" }
    Stop-ServerProcess $proc
    Remove-Item "config\test_missing_listen.conf" -Force -ErrorAction SilentlyContinue

    # 8c: Nonexistent document root
    $configContent = @"
server {
    listen 8087
    name server1
    document_root ./www/nonexistent_dir_12345
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_bad_docroot.conf", $configContent, [System.Text.Encoding]::UTF8)
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_bad_docroot.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2
    $err8c = if (Test-Path "test_error.txt") { (Get-Content "test_error.txt" -Raw) } else { "" }
    Stop-ServerProcess $proc
    Remove-Item "config\test_bad_docroot.conf" -Force -ErrorAction SilentlyContinue

    $c1 = ($err8a -match "Conflicting server_name")
    $c2 = ($err8b -match "missing 'listen' directive")
    $c3 = ($err8c -match "Document root does not exist")

    if ($c1 -and $c2 -and $c3) {
        Write-Host "  PASS: Conflicting server_name, missing listen, and bad docroot all fail clearly" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Validation checks failed (conflict: $c1, missing listen: $c2, bad docroot: $c3)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 8 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
}

# Test 9: Multiple configurations sharing a port do not cause bind errors
Write-Host "`nTest 9: Multiple configurations sharing a port do not cause bind errors" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8088
    name server1
    server_name a.local
    document_root ./www/server1
}
server {
    listen 8088
    name server2
    server_name b.local
    document_root ./www/server2
}
server {
    listen 8088
    name server3
    server_name c.local
    document_root ./www
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\test_shared_nobind.conf", $configContent, [System.Text.Encoding]::UTF8)
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_shared_nobind.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $outContent = if (Test-Path "test_output.txt") { (Get-Content "test_output.txt" -Raw) } else { "" }
    $errContent = if (Test-Path "test_error.txt") { (Get-Content "test_error.txt" -Raw) } else { "" }

    # Sockets should bind exactly once on port 8088 without bind error
    $respA = Invoke-WebRequest -Uri "http://localhost:8088/" -Headers @{"Host"="a.local"} -UseBasicParsing -TimeoutSec 5
    $respB = Invoke-WebRequest -Uri "http://localhost:8088/" -Headers @{"Host"="b.local"} -UseBasicParsing -TimeoutSec 5
    $respC = Invoke-WebRequest -Uri "http://localhost:8088/" -Headers @{"Host"="c.local"} -UseBasicParsing -TimeoutSec 5

    $bindCount = ([regex]::Matches($outContent, "Server listening on port 8088")).Count
    $errLen = if ($errContent) { $errContent.Trim().Length } else { 0 }

    if ($bindCount -eq 1 -and $errLen -eq 0 -and $respA.Content -match "Server 1" -and $respB.Content -match "Server 2") {
        Write-Host "  PASS: Sockets bound exactly once per unique port, all servers reachable" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Port bind verification failed (bindCount: $bindCount, err: $errContent)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 9 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_shared_nobind.conf" -Force -ErrorAction SilentlyContinue
}

# Test 10: An invalid server block does not prevent valid blocks from starting
Write-Host "`nTest 10: Valid server remains available when a sibling block is invalid" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8089
    name survivor
    server_name survivor.local
    default_server
    document_root ./www/server1
}
server {
    listen 8089
    name invalid
    server_name invalid.local
    document_root ./www/does_not_exist
}
"@
    [System.IO.File]::WriteAllText("config\test_invalid_sibling.conf", $configContent, [System.Text.Encoding]::UTF8)
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_invalid_sibling.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"

    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($proc.HasExited) { break }
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8089/" -Headers @{"Host"="survivor.local"} -UseBasicParsing -TimeoutSec 1
            $ready = $response.StatusCode -eq 200 -and $response.Content -match "Server 1"
            if ($ready) { break }
        } catch {
            Start-Sleep -Milliseconds 200
        }
    }
    $outContent = if (Test-Path "test_output.txt") { Get-Content "test_output.txt" -Raw } else { "" }
    $errContent = if (Test-Path "test_error.txt") { Get-Content "test_error.txt" -Raw } else { "" }
    $invalidSkipped = ($outContent + $errContent) -match "Skipping Invalid server block"

    if ($ready -and $invalidSkipped) {
        Write-Host "  PASS: Invalid sibling was skipped and the valid server served requests" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Valid-server isolation failed (ready: $ready, invalid skipped: $invalidSkipped)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 10 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_invalid_sibling.conf" -Force -ErrorAction SilentlyContinue
}

# Test 11: A repeated port in one server block is rejected
Write-Host "`nTest 11: Duplicate listen port in one server block is rejected" -ForegroundColor Yellow
$proc = $null
try {
    $configContent = @"
server {
    listen 8090 8090
    name duplicate_port
    document_root ./www/server1
}
"@
    [System.IO.File]::WriteAllText("config\test_duplicate_listen.conf", $configContent, [System.Text.Encoding]::UTF8)
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/test_duplicate_listen.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    $proc.WaitForExit(5000) | Out-Null
    $outContent = if (Test-Path "test_output.txt") { Get-Content "test_output.txt" -Raw } else { "" }
    $errContent = if (Test-Path "test_error.txt") { Get-Content "test_error.txt" -Raw } else { "" }
    $rejected = ($outContent + $errContent) -match "Duplicate listen port 8090"
    if ($proc.HasExited -and $proc.ExitCode -ne 0 -and $rejected) {
        Write-Host "  PASS: Duplicate port declaration was rejected with a clear error" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Duplicate port was not rejected (exit: $($proc.ExitCode), message: $outContent $errContent)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 11 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "config\test_duplicate_listen.conf" -Force -ErrorAction SilentlyContinue
}

# Cleanup test files
Remove-Item "test_output.txt" -Force -ErrorAction SilentlyContinue
Remove-Item "test_error.txt" -Force -ErrorAction SilentlyContinue

Write-Host "`n=== Multi-Server Tests Summary: $passCount passed, $failCount failed ===" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
if ($failCount -gt 0) {
    exit 1
}
