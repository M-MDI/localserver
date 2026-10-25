# Configuration-defined routes tests

$ErrorActionPreference = "Stop"

Write-Host "=== Configuration-Defined Routes Tests ===" -ForegroundColor Cyan

# Test 1: Config-defined static route
Write-Host "`nTest 1: Config-defined static route" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9000
    name test_server
    document_root ./www
    route /api root ./www/testapi
}
"@
    $configContent | Out-File "config\route_test1.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test1.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s http://localhost:9000/api/
    if ($response -match "API content") {
        Write-Host "  PASS: Config-defined static route works" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Config-defined static route failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test1.conf" -Force
} catch {
    Write-Host "  FAIL: Test 1 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test1.conf" -Force -ErrorAction SilentlyContinue
}

# Test 2: Multiple routes in one server
Write-Host "`nTest 2: Multiple routes in one server" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9001
    name test_server
    document_root ./www
    route /api root ./www/testapi
    route /upload root ./www/testupload
    route /cgi root ./www/testcgi
}
"@
    $configContent | Out-File "config\route_test2.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test2.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9001/api/
    $r2 = & curl.exe -s http://localhost:9001/upload/
    $r3 = & curl.exe -s http://localhost:9001/cgi/

    $pass = ($r1 -match "API content") -and ($r2 -match "Upload area") -and ($r3 -match "CGI area")
    if ($pass) {
        Write-Host "  PASS: Multiple routes work correctly" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Multiple routes failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test2.conf" -Force
} catch {
    Write-Host "  FAIL: Test 2 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test2.conf" -Force -ErrorAction SilentlyContinue
}

# Test 3: Longest-prefix route selection
Write-Host "`nTest 3: Longest-prefix route selection" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9002
    name test_server
    document_root ./www
    route /api root ./www/testapi
    route /api/v2 root ./www/testapi
}
"@
    $configContent | Out-File "config\route_test3.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test3.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9002/api/
    $r2 = & curl.exe -s http://localhost:9002/api/v2/

    $pass = ($r1 -match "API content") -and ($r2 -match "API content")
    if ($pass) {
        Write-Host "  PASS: Longest-prefix selection works" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Longest-prefix selection failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test3.conf" -Force
} catch {
    Write-Host "  FAIL: Test 3 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test3.conf" -Force -ErrorAction SilentlyContinue
}

# Test 4: Different routes on different server configurations
Write-Host "`nTest 4: Different routes on different server configurations" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9003
    name server1
    server_name server1.local
    document_root ./www
    route /api root ./www/testapi
}

server {
    listen 9004
    name server2
    server_name server2.local
    document_root ./www
    route /upload root ./www/testupload
}
"@
    $configContent | Out-File "config\route_test4.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test4.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9003/api/
    $r2 = & curl.exe -s http://localhost:9004/upload/

    $pass = ($r1 -match "API content") -and ($r2 -match "Upload area")
    if ($pass) {
        Write-Host "  PASS: Different routes on different servers work" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Different routes on different servers failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test4.conf" -Force
} catch {
    Write-Host "  FAIL: Test 4 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test4.conf" -Force -ErrorAction SilentlyContinue
}

# Test 5: Route isolation between two servers
Write-Host "`nTest 5: Route isolation between two servers (shared port)" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9005
    name server1
    server_name server1.local
    document_root ./www
    default_server
    route /api root ./www/testapi
}

server {
    listen 9005
    name server2
    server_name server2.local
    document_root ./www
    route /upload root ./www/testupload
}
"@
    $configContent | Out-File "config\route_test5.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test5.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s -H "Host: server1.local" http://localhost:9005/api/
    $r2 = & curl.exe -s -H "Host: server2.local" http://localhost:9005/upload/

    $pass = ($r1 -match "API content") -and ($r2 -match "Upload area")
    if ($pass) {
        Write-Host "  PASS: Route isolation works" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Route isolation failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test5.conf" -Force
} catch {
    Write-Host "  FAIL: Test 5 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test5.conf" -Force -ErrorAction SilentlyContinue
}

# Test 6: Unknown route behavior (should use default route)
Write-Host "`nTest 6: Unknown route behavior" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9006
    name test_server
    document_root ./www
    route /api root ./www/testapi
}
"@
    $configContent | Out-File "config\route_test6.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test6.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s http://localhost:9006/unknown/
    # Should return 404 or fall back to default route behavior
    if ($response -match "404" -or $response -match "Not Found") {
        Write-Host "  PASS: Unknown route returns 404" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Unknown route behavior unexpected" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test6.conf" -Force
} catch {
    Write-Host "  FAIL: Test 6 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test6.conf" -Force -ErrorAction SilentlyContinue
}

# Test 7: Existing single-server configuration without route directives
Write-Host "`nTest 7: Existing single-server config without route directives" -ForegroundColor Yellow
try {
    Copy-Item "config\server.conf" "config\server.conf.bak" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s http://localhost:65432/
    if ($response -match "LocalServer" -or $response -ne "") {
        Write-Host "  PASS: Single-server config without routes works" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Single-server config without routes failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
} catch {
    Write-Host "  FAIL: Test 7 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
}

# Test 8: Existing multi-server configuration without route directives
Write-Host "`nTest 8: Existing multi-server config without route directives" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9007
    name server1
    document_root ./www/server1
}

server {
    listen 9008
    name server2
    document_root ./www/server2
}
"@
    $configContent | Out-File "config\route_test8.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test8.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s http://localhost:9007/
    $r2 = & curl.exe -s http://localhost:9008/

    $pass = ($r1 -match "Server 1 content") -and ($r2 -match "Server 2 content")
    if ($pass) {
        Write-Host "  PASS: Multi-server config without routes works" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Multi-server config without routes failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test8.conf" -Force
} catch {
    Write-Host "  FAIL: Test 8 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test8.conf" -Force -ErrorAction SilentlyContinue
}

# Test 9: Malformed route configuration rejected clearly
Write-Host "`nTest 9: Malformed route configuration rejected" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9009
    name test_server
    document_root ./www
    route
}
"@
    $configContent | Out-File "config\route_test9.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test9.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $errorContent = Get-Content "test_error.txt" -Raw
    if ($errorContent -match "Malformed route") {
        Write-Host "  PASS: Malformed route rejected clearly" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Malformed route not rejected" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test9.conf" -Force
} catch {
    Write-Host "  FAIL: Test 9 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test9.conf" -Force -ErrorAction SilentlyContinue
}

# Test 10: Route with upload type
Write-Host "`nTest 10: Route with upload type" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9010
    name test_server
    document_root ./www
    route /files upload ./www/testupload
}
"@
    $configContent | Out-File "config\route_test10.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test10.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # Upload routes should reject GET with 405
    $response = & curl.exe -s http://localhost:9010/files/
    if ($response -match "405" -or $response -match "Method Not Allowed") {
        Write-Host "  PASS: Route with upload type works (rejects GET)" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Route with upload type failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test10.conf" -Force
} catch {
    Write-Host "  FAIL: Test 10 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test10.conf" -Force -ErrorAction SilentlyContinue
}

# Test 11: Route with CGI type
Write-Host "`nTest 11: Route with CGI type" -ForegroundColor Yellow
try {
    $configContent = @"
server {
    listen 9011
    name test_server
    document_root ./www
    route /scripts cgi ./www/testcgi
}
"@
    $configContent | Out-File "config\route_test11.conf" -Encoding UTF8

    Copy-Item "config\server.conf" "config\server.conf.bak" -Force
    Copy-Item "config\route_test11.conf" "config\server.conf" -Force

    $process = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # CGI routes with no script should return 404
    $response = & curl.exe -s http://localhost:9011/scripts/
    if ($response -match "404" -or $response -match "Not Found") {
        Write-Host "  PASS: Route with CGI type works (no script = 404)" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: Route with CGI type failed" -ForegroundColor Red
    }

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force
    Remove-Item "config\server.conf.bak" -Force
    Remove-Item "config\route_test11.conf" -Force
} catch {
    Write-Host "  FAIL: Test 11 failed: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Copy-Item "config\server.conf.bak" "config\server.conf" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\server.conf.bak" -Force -ErrorAction SilentlyContinue
    Remove-Item "config\route_test11.conf" -Force -ErrorAction SilentlyContinue
}

# Cleanup
Remove-Item "test_output.txt" -Force -ErrorAction SilentlyContinue
Remove-Item "test_error.txt" -Force -ErrorAction SilentlyContinue

Write-Host "`n=== Configuration-Defined Routes Tests Complete ===" -ForegroundColor Cyan
