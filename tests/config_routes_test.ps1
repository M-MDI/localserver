# Configuration-defined routes tests for LocalServer
$ErrorActionPreference = "Stop"

Write-Host "=== Configuration-Defined Routes Tests ===" -ForegroundColor Cyan

function Stop-ServerProcess($proc) {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
    }
}

function Kill-PortProcesses($port) {
    try {
        $conns = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
        if ($conns) {
            foreach ($c in $conns) {
                if ($c.OwningProcess) {
                    Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
                }
            }
            Start-Sleep -Milliseconds 500
        }
    } catch {}
}

$passCount = 0
$failCount = 0
$skipCount = 0

# Test 1: One configured static route
Write-Host "`nTest 1: One configured static route" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9000
try {
    $configContent = @"
server {
    listen 9000
    name test_server
    document_root ./www
    route /api static ./www/routes_test/api
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test1.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test1.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s http://localhost:9000/api/version.txt
    if ($response -match "API v1 content") {
        Write-Host "  PASS: Configured static route correctly serves file" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Configured static route failed (Got: $response)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 1 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test1.conf" -Force -ErrorAction SilentlyContinue
}

# Test 2: Multiple routes in one server
Write-Host "`nTest 2: Multiple routes in one server" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9001
try {
    $configContent = @"
server {
    listen 9001
    name test_server
    document_root ./www
    route /api static ./www/routes_test/api
    route /docs static ./www/routes_test/docs
    route /static static ./www/routes_test/static
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test2.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test2.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9001/api/version.txt
    $r2 = & curl.exe -s http://localhost:9001/docs/doc.txt
    $r3 = & curl.exe -s http://localhost:9001/static/hello.txt

    $pass = ($r1 -match "API v1 content") -and ($r2 -match "Documentation content") -and ($r3 -match "Static route content")
    if ($pass) {
        Write-Host "  PASS: Multiple routes resolve independently in one server" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Multiple routes failed (r1: $r1, r2: $r2, r3: $r3)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 2 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test2.conf" -Force -ErrorAction SilentlyContinue
}

# Test 3: Longest-prefix route selection
Write-Host "`nTest 3: Longest-prefix route selection" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9002
try {
    $configContent = @"
server {
    listen 9002
    name test_server
    document_root ./www
    route /api static ./www/routes_test/api
    route /api/v2 static ./www/routes_test/api_v2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test3.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test3.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9002/api/version.txt
    $r2 = & curl.exe -s http://localhost:9002/api/v2/version.txt

    $pass = ($r1 -match "API v1 content") -and ($r2 -match "API v2 content")
    if ($pass) {
        Write-Host "  PASS: Longest-prefix correctly distinguishes /api vs /api/v2" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Longest-prefix selection failed (r1: $r1, r2: $r2)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 3 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test3.conf" -Force -ErrorAction SilentlyContinue
}

# Test 4: Path boundary handling (/api must not match /apix)
Write-Host "`nTest 4: Path boundary validation (/api does not match /apix)" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9003
try {
    $configContent = @"
server {
    listen 9003
    name test_server
    document_root ./www
    route /api static ./www/routes_test/api
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test4.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test4.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $rValid = & curl.exe -s -i http://localhost:9003/api/version.txt
    $rBoundary = & curl.exe -s -i http://localhost:9003/apix

    $pass = ($rValid -match "200 OK") -and ($rBoundary -match "404 Not Found")
    if ($pass) {
        Write-Host "  PASS: /apix is rejected with 404 when only /api is configured" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Path boundary check failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 4 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test4.conf" -Force -ErrorAction SilentlyContinue
}

# Test 5: Different routes on different server configurations (multi-port)
Write-Host "`nTest 5: Different routes on different server configurations" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9004
Kill-PortProcesses 9005
try {
    $configContent = @"
server {
    listen 9004
    name server1
    document_root ./www
    route /api static ./www/routes_test/api
}

server {
    listen 9005
    name server2
    document_root ./www
    route /docs static ./www/routes_test/docs
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test5.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test5.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9004/api/version.txt
    $r2 = & curl.exe -s http://localhost:9005/docs/doc.txt

    $pass = ($r1 -match "API v1 content") -and ($r2 -match "Documentation content")
    if ($pass) {
        Write-Host "  PASS: Server 1 has /api and Server 2 has /docs" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Different routes on different servers failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 5 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test5.conf" -Force -ErrorAction SilentlyContinue
}

# Test 6: Route isolation between two servers sharing a port
Write-Host "`nTest 6: Route isolation between two servers (shared port)" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9006
try {
    $configContent = @"
server {
    listen 9006
    name server1
    server_name server1.local
    document_root ./www
    default_server
    route /api static ./www/routes_test/server1
}

server {
    listen 9006
    name server2
    server_name server2.local
    document_root ./www
    route /api static ./www/routes_test/server2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test6.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test6.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s -H "Host: server1.local" http://localhost:9006/api/secret.txt
    $r2 = & curl.exe -s -H "Host: server2.local" http://localhost:9006/api/secret.txt

    $pass = ($r1 -match "Server 1 Secret") -and ($r2 -match "Server 2 Secret")
    if ($pass) {
        Write-Host "  PASS: Route isolation maintained per server config on shared port" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Route isolation failed (r1: $r1, r2: $r2)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 6 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test6.conf" -Force -ErrorAction SilentlyContinue
}

# Test 7: Unknown route behavior
Write-Host "`nTest 7: Unknown route behavior" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9007
try {
    $configContent = @"
server {
    listen 9007
    name test_server
    document_root ./www
    route /api static ./www/routes_test/api
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test7.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test7.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s -i http://localhost:9007/unconfigured/route
    if ($response -match "404 Not Found") {
        Write-Host "  PASS: Unconfigured route returns 404 Not Found" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Unknown route did not return 404" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 7 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test7.conf" -Force -ErrorAction SilentlyContinue
}

# Test 8: Backward compatibility with existing single-server configuration
Write-Host "`nTest 8: Backward compatibility with existing single-server configuration" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 65432
try {
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/server.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s http://localhost:65432/
    if ($response -match "LocalServer" -or $response -match "Index of" -or $response -ne "") {
        Write-Host "  PASS: Single-server mode works as fallback without route directives" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Single-server mode failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 8 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
}

# Test 9: Backward compatibility with existing multi-server configuration
Write-Host "`nTest 9: Backward compatibility with existing multi-server configuration" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9008
Kill-PortProcesses 9009
try {
    $configContent = @"
server {
    listen 9008
    name server1
    document_root ./www/server1
}

server {
    listen 9009
    name server2
    document_root ./www/server2
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test9.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test9.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9008/
    $r2 = & curl.exe -s http://localhost:9009/

    $pass = ($r1 -match "Server 1 content") -and ($r2 -match "Server 2 content")
    if ($pass) {
        Write-Host "  PASS: Multi-server mode works as fallback without route directives" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Multi-server mode without routes failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 9 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test9.conf" -Force -ErrorAction SilentlyContinue
}

# Test 10: Invalid route configurations rejected with clear errors
Write-Host "`nTest 10: Invalid route configurations rejected with clear errors" -ForegroundColor Yellow

$invalidTests = @(
    @{
        name = "10a: Missing arguments"
        content = "server {`n    listen 9010`n    document_root ./www`n    route`n}"
        expected = "Malformed route"
    },
    @{
        name = "10b: Path without leading slash"
        content = "server {`n    listen 9010`n    document_root ./www`n    route api root`n}"
        expected = "Invalid route path"
    },
    @{
        name = "10c: Path traversal in route path"
        content = "server {`n    listen 9010`n    document_root ./www`n    route /../secret root`n}"
        expected = "Path traversal"
    },
    @{
        name = "10d: Invalid action"
        content = "server {`n    listen 9010`n    document_root ./www`n    route / unknown_action`n}"
        expected = "Invalid route action"
    },
    @{
        name = "10e: Nonexistent route document root"
        content = "server {`n    listen 9010`n    document_root ./www`n    route /api static ./www/nonexistent_dir_12345`n}"
        expected = "Route document root does not exist"
    },
    @{
        name = "10f: Duplicate route path in same server"
        content = "server {`n    listen 9010`n    document_root ./www`n    route /api root ./www/testapi`n    route /api static ./www/testapi`n}"
        expected = "Duplicate route path"
    }
)

$allInvalidPassed = $true
foreach ($t in $invalidTests) {
    $configFile = "$PSScriptRoot\..\config\route_invalid.conf"
    [System.IO.File]::WriteAllText($configFile, $t.content, [System.Text.Encoding]::UTF8)

    $errOut = "test_invalid_error.txt"
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_invalid.conf" -PassThru -NoNewWindow -RedirectStandardError $errOut
    Start-Sleep -Seconds 1
    Stop-ServerProcess $proc

    $errText = if (Test-Path $errOut) { Get-Content $errOut -Raw } else { "" }
    Remove-Item $errOut -Force -ErrorAction SilentlyContinue
    Remove-Item $configFile -Force -ErrorAction SilentlyContinue

    if ($errText -match $t.expected) {
        Write-Host "    PASS: $($t.name) rejected with expected message" -ForegroundColor Green
    } else {
        Write-Host "    FAIL: $($t.name) was not rejected as expected. Error was: $errText" -ForegroundColor Red
        $allInvalidPassed = $false
    }
}

if ($allInvalidPassed) {
    Write-Host "  PASS: All invalid route configurations rejected clearly" -ForegroundColor Green
    $passCount++
} else {
    Write-Host "  FAIL: Some invalid route configurations were not rejected" -ForegroundColor Red
    $failCount++
}

# Test 11: Route with upload type
Write-Host "`nTest 11: Route with upload type" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9011
try {
    $configContent = @"
server {
    listen 9011
    name test_server
    document_root ./www
    route /upload-area upload ./www/testupload
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test11.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test11.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # GET to upload route should be rejected with 405 Method Not Allowed
    $rGet = & curl.exe -s -i http://localhost:9011/upload-area/
    # POST to upload route should succeed
    $uploadFile = "$PSScriptRoot\..\www\testupload\uploaded_sample.txt"
    Remove-Item $uploadFile -Force -ErrorAction SilentlyContinue
    $rPost = & curl.exe -s -i -X POST --data "uploaded sample text" http://localhost:9011/upload-area/uploaded_sample.txt

    $pass = ($rGet -match "405 Method Not Allowed") -and ($rPost -match "201 Created") -and (Test-Path $uploadFile)
    if ($pass) {
        Write-Host "  PASS: Upload route enforces POST and successfully writes upload" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Route with upload type failed" -ForegroundColor Red
        $failCount++
    }
    Remove-Item $uploadFile -Force -ErrorAction SilentlyContinue
} catch {
    Write-Host "  FAIL: Test 11 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test11.conf" -Force -ErrorAction SilentlyContinue
}

# Test 12: Route with CGI type
Write-Host "`nTest 12: Route with CGI type" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9012
try {
    $configContent = @"
server {
    listen 9012
    name test_server
    document_root ./www
    route /mycgi cgi ./www/cgi-bin
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test12.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test12.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $rEmpty = & curl.exe -s -i http://localhost:9012/mycgi/
    $rScript = & curl.exe -s -i http://localhost:9012/mycgi/hello.py

    $pass = ($rEmpty -match "404 Not Found") -and ($rScript -match "200 OK") -and ($rScript -match "Hello from CGI!")
    if ($pass) {
        Write-Host "  PASS: Custom CGI route properly executes script under configured prefix" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Route with CGI type failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 12 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test12.conf" -Force -ErrorAction SilentlyContinue
}

# Test 13: DELETE method behavior on configured route
Write-Host "`nTest 13: DELETE method on configured route" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9013
try {
    $configContent = @"
server {
    listen 9013
    name test_server
    document_root ./www
    route /files static ./www/testdir
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\route_test13.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/route_test13.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $deleteTarget = "$PSScriptRoot\..\www\testdir\to_delete.txt"
    [System.IO.File]::WriteAllText($deleteTarget, "delete me", [System.Text.Encoding]::UTF8)

    $rDelete = & curl.exe -s -i -X DELETE http://localhost:9013/files/to_delete.txt
    $deleted = -not (Test-Path $deleteTarget)

    if ($rDelete -match "204 No Content" -and $deleted) {
        Write-Host "  PASS: DELETE on configured route deletes file and returns 204" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: DELETE on configured route failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 13 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\route_test13.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "$PSScriptRoot\..\www\testdir\to_delete.txt" -Force -ErrorAction SilentlyContinue
}

# Cleanup temporary logs
Remove-Item "test_output.txt" -Force -ErrorAction SilentlyContinue
Remove-Item "test_error.txt" -Force -ErrorAction SilentlyContinue

Write-Host "`n=== Configuration-Defined Routes Tests Summary: $passCount passed, $skipCount skipped, $failCount failed ===" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
if ($failCount -gt 0) {
    exit 1
}
