# HTTP Redirects Verification Tests for LocalServer
$ErrorActionPreference = "Stop"

Write-Host "=== HTTP Redirects Tests ===" -ForegroundColor Cyan

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

# Test 1: Permanent redirect (301)
Write-Host "`nTest 1: Permanent redirect (301)" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9021
try {
    $configContent = @"
server {
    listen 9021
    name test_server
    document_root ./www
    redirect /old-page /new-page 301
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test1.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test1.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $response = & curl.exe -s -i http://localhost:9021/old-page

    $statusMatch = $response -match "HTTP/1\.1 301 Moved Permanently"
    $locMatch = $response -match "Location: /new-page"
    $bodyMatch = $response -match "Redirecting to /new-page"

    if ($statusMatch -and $locMatch -and $bodyMatch) {
        Write-Host "  PASS: 301 Moved Permanently with Location: /new-page returned" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: 301 redirect response invalid (Got:`n$response)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 1 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test1.conf" -Force -ErrorAction SilentlyContinue
}

# Test 2: Temporary redirect (302) - explicit and default
Write-Host "`nTest 2: Temporary redirect (302) explicit and default" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9022
try {
    $configContent = @"
server {
    listen 9022
    name test_server
    document_root ./www
    redirect /explicit-temp /dest-explicit 302
    redirect /default-temp /dest-default
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test2.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test2.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r1 = & curl.exe -s -i http://localhost:9022/explicit-temp
    $r2 = & curl.exe -s -i http://localhost:9022/default-temp

    $p1 = ($r1 -match "HTTP/1\.1 302 Found") -and ($r1 -match "Location: /dest-explicit")
    $p2 = ($r2 -match "HTTP/1\.1 302 Found") -and ($r2 -match "Location: /dest-default")

    if ($p1 -and $p2) {
        Write-Host "  PASS: Both explicit 302 and default 302 return 302 Found" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: 302 temporary redirect failed (r1: $r1, r2: $r2)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 2 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test2.conf" -Force -ErrorAction SilentlyContinue
}

# Test 3: 303, 307, and 308 responses
Write-Host "`nTest 3: 303, 307, and 308 responses" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9023
try {
    $configContent = @"
server {
    listen 9023
    name test_server
    document_root ./www
    redirect /see-other /dest-303 303
    redirect /temp-preserve /dest-307 307
    redirect /perm-preserve /dest-308 308
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test3.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test3.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $r303 = & curl.exe -s -i http://localhost:9023/see-other
    $r307 = & curl.exe -s -i http://localhost:9023/temp-preserve
    $r308 = & curl.exe -s -i http://localhost:9023/perm-preserve

    $p303 = ($r303 -match "HTTP/1\.1 303 See Other") -and ($r303 -match "Location: /dest-303")
    $p307 = ($r307 -match "HTTP/1\.1 307 Temporary Redirect") -and ($r307 -match "Location: /dest-307")
    $p308 = ($r308 -match "HTTP/1\.1 308 Permanent Redirect") -and ($r308 -match "Location: /dest-308")

    if ($p303 -and $p307 -and $p308) {
        Write-Host "  PASS: 303 See Other, 307 Temporary Redirect, and 308 Permanent Redirect all work" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Status codes 303/307/308 check failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 3 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test3.conf" -Force -ErrorAction SilentlyContinue
}

# Test 4: Correct Location header with absolute and relative URLs, query string matching
Write-Host "`nTest 4: Correct Location header (absolute, relative, query preserved match)" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9024
try {
    $configContent = @"
server {
    listen 9024
    name test_server
    document_root ./www
    redirect /external https://example.com/target 301
    redirect /local /app/dashboard 302
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test4.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test4.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    $rExt = & curl.exe -s -i http://localhost:9024/external
    $rLoc = & curl.exe -s -i "http://localhost:9024/local?tab=account&ref=email"

    $pExt = ($rExt -match "Location: https://example.com/target")
    $pLoc = ($rLoc -match "Location: /app/dashboard")

    if ($pExt -and $pLoc) {
        Write-Host "  PASS: Correct Location headers generated for both absolute and relative targets" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Location header check failed (ext: $rExt, loc: $rLoc)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 4 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test4.conf" -Force -ErrorAction SilentlyContinue
}

# Test 5: Redirect scoped to one virtual host (multi-server isolation)
Write-Host "`nTest 5: Redirect scoped to one virtual host" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9025
try {
    $configContent = @"
server {
    listen 9025
    name server1
    server_name server1.local
    document_root ./www
    default_server
    redirect /promo /summer-sale 302
}

server {
    listen 9025
    name server2
    server_name server2.local
    document_root ./www
    redirect /promo /winter-sale 301
    redirect /exclusive /s2-portal 307
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test5.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test5.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # server1 /promo -> 302 /summer-sale
    $r1 = & curl.exe -s -i -H "Host: server1.local" http://localhost:9025/promo
    # server2 /promo -> 301 /winter-sale
    $r2 = & curl.exe -s -i -H "Host: server2.local" http://localhost:9025/promo
    # server1 /exclusive -> should NOT redirect (returns 404 since /exclusive doesn't exist)
    $r3 = & curl.exe -s -i -H "Host: server1.local" http://localhost:9025/exclusive
    # server2 /exclusive -> 307 /s2-portal
    $r4 = & curl.exe -s -i -H "Host: server2.local" http://localhost:9025/exclusive

    $p1 = ($r1 -match "302 Found") -and ($r1 -match "Location: /summer-sale")
    $p2 = ($r2 -match "301 Moved Permanently") -and ($r2 -match "Location: /winter-sale")
    $p3 = ($r3 -match "404 Not Found") -or ($r3 -notmatch "Location:")
    $p4 = ($r4 -match "307 Temporary Redirect") -and ($r4 -match "Location: /s2-portal")

    if ($p1 -and $p2 -and $p3 -and $p4) {
        Write-Host "  PASS: Redirects are strictly scoped to the matching virtual host" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Virtual host redirect scoping failed" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 5 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test5.conf" -Force -ErrorAction SilentlyContinue
}

# Test 6: Redirect versus regular-route precedence
Write-Host "`nTest 6: Redirect versus regular-route precedence" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9026
try {
    $configContent = @"
server {
    listen 9026
    name test_server
    document_root ./www
    # Equal length: redirect /docs takes precedence over route /docs
    route /docs static ./www/routes_test/docs
    redirect /docs /documentation 301

    # More specific redirect /api/legacy takes precedence over route /api
    route /api static ./www/routes_test/api
    redirect /api/legacy /api/v2 301

    # More specific route /files/special takes precedence over redirect /files
    redirect /files /new-files 302
    route /files/special static ./www/routes_test/static
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test6.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test6.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # Equal length test: /docs should redirect, avoiding the static file
    $rEqual = & curl.exe -s -i http://localhost:9026/docs
    $pEqual = ($rEqual -match "301 Moved Permanently") -and ($rEqual -match "Location: /documentation")

    # More specific redirect test: /api/legacy/something should redirect
    $rMoreSpecRedir = & curl.exe -s -i http://localhost:9026/api/legacy/something
    $pMoreSpecRedir = ($rMoreSpecRedir -match "301 Moved Permanently") -and ($rMoreSpecRedir -match "Location: /api/v2")

    # Route normal access under /api: /api/version.txt should serve static content
    $rRouteNorm = & curl.exe -s -i http://localhost:9026/api/version.txt
    $pRouteNorm = ($rRouteNorm -match "200 OK") -and ($rRouteNorm -match "API v1 content")

    # More specific route test: /files/special/hello.txt should serve static content
    $rMoreSpecRoute = & curl.exe -s -i http://localhost:9026/files/special/hello.txt
    $pMoreSpecRoute = ($rMoreSpecRoute -match "200 OK") -and ($rMoreSpecRoute -match "Static route content")

    # Sibling under /files: /files/other should hit redirect /files
    $rLessSpecRedir = & curl.exe -s -i http://localhost:9026/files/other
    $pLessSpecRedir = ($rLessSpecRedir -match "302 Found") -and ($rLessSpecRedir -match "Location: /new-files")

    if ($pEqual -and $pMoreSpecRedir -and $pRouteNorm -and $pMoreSpecRoute -and $pLessSpecRedir) {
        Write-Host "  PASS: Overlapping precedence deterministically selects longest-prefix, with redirect winning equal length" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Route vs redirect precedence failed (pEqual: $pEqual, pMoreSpecRedir: $pMoreSpecRedir, pRouteNorm: $pRouteNorm, pMoreSpecRoute: $pMoreSpecRoute, pLessSpecRedir: $pLessSpecRedir)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 6 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test6.conf" -Force -ErrorAction SilentlyContinue
}

# Test 7: Invalid redirect configurations rejected at startup
Write-Host "`nTest 7: Invalid redirect configurations rejected at startup" -ForegroundColor Yellow

$invalidRedirects = @(
    @{
        name = "7a: Missing arguments"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect`n}"
        expected = "Malformed redirect directive"
    },
    @{
        name = "7b: Missing target"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect /old`n}"
        expected = "missing destination"
    },
    @{
        name = "7c: Path without leading slash"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect old /new 301`n}"
        expected = "Invalid redirect path"
    },
    @{
        name = "7d: Path traversal in redirect path"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect /../secret /new 301`n}"
        expected = "Path traversal"
    },
    @{
        name = "7e: Unsupported status code 200"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect /old /new 200`n}"
        expected = "Unsupported redirect status code"
    },
    @{
        name = "7f: Unsupported status code 305"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect /old /new 305`n}"
        expected = "Unsupported redirect status code"
    },
    @{
        name = "7g: Non-numeric status code"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect /old /new invalid_code`n}"
        expected = "Invalid redirect status code"
    },
    @{
        name = "7h: Duplicate redirect path in same server"
        content = "server {`n    listen 9027`n    document_root ./www`n    redirect /old /new1 301`n    redirect /old /new2 302`n}"
        expected = "Duplicate redirect path"
    }
)

$allInvalidPassed = $true
foreach ($t in $invalidRedirects) {
    $configFile = "$PSScriptRoot\..\config\redirect_invalid.conf"
    [System.IO.File]::WriteAllText($configFile, $t.content, [System.Text.Encoding]::UTF8)

    $errOut = "test_redirect_invalid_error.txt"
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_invalid.conf" -PassThru -NoNewWindow -RedirectStandardError $errOut
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
    Write-Host "  PASS: All invalid redirect configurations rejected clearly" -ForegroundColor Green
    $passCount++
} else {
    Write-Host "  FAIL: Some invalid redirect configurations were not rejected" -ForegroundColor Red
    $failCount++
}

# Test 8: Header-injection attempts rejected at startup
Write-Host "`nTest 8: Header-injection attempts rejected" -ForegroundColor Yellow
$injectionTests = @(
    @{
        name = "8a: Literal \r\n escape injection in target"
        content = "server {`n    listen 9028`n    document_root ./www`n    redirect /old /new\r\nSet-Cookie:evil=1 302`n}"
        expected = "header injection attempt detected"
    },
    @{
        name = "8b: URL-encoded CRLF %0d%0a injection in target"
        content = "server {`n    listen 9028`n    document_root ./www`n    redirect /old /new%0d%0aSet-Cookie:evil=1 302`n}"
        expected = "header injection attempt detected"
    }
)

$allInjectionsPassed = $true
foreach ($t in $injectionTests) {
    $configFile = "$PSScriptRoot\..\config\redirect_injection.conf"
    [System.IO.File]::WriteAllText($configFile, $t.content, [System.Text.Encoding]::UTF8)

    $errOut = "test_redirect_injection_error.txt"
    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_injection.conf" -PassThru -NoNewWindow -RedirectStandardError $errOut
    Start-Sleep -Seconds 1
    Stop-ServerProcess $proc

    $errText = if (Test-Path $errOut) { Get-Content $errOut -Raw } else { "" }
    Remove-Item $errOut -Force -ErrorAction SilentlyContinue
    Remove-Item $configFile -Force -ErrorAction SilentlyContinue

    if ($errText -match $t.expected) {
        Write-Host "    PASS: $($t.name) rejected with expected error" -ForegroundColor Green
    } else {
        Write-Host "    FAIL: $($t.name) was not rejected as expected. Error was: $errText" -ForegroundColor Red
        $allInjectionsPassed = $false
    }
}

if ($allInjectionsPassed) {
    Write-Host "  PASS: Header injection attempts rejected at startup" -ForegroundColor Green
    $passCount++
} else {
    Write-Host "  FAIL: Some header injection attempts were not rejected" -ForegroundColor Red
    $failCount++
}

# Test 9: Existing requests without redirects continue working
Write-Host "`nTest 9: Existing requests without redirects continue working" -ForegroundColor Yellow
$proc = $null
Kill-PortProcesses 9029
try {
    $configContent = @"
server {
    listen 9029
    name test_server
    document_root ./www
    redirect /legacy-info /new-info 301
}
"@
    [System.IO.File]::WriteAllText("$PSScriptRoot\..\config\redirect_test9.conf", $configContent, [System.Text.Encoding]::UTF8)

    $proc = Start-Process -FilePath "java" -ArgumentList "-cp bin Main config/redirect_test9.conf" -PassThru -NoNewWindow -RedirectStandardOutput "test_output.txt" -RedirectStandardError "test_error.txt"
    Start-Sleep -Seconds 2

    # File that exists: hello.txt should return 200 OK
    $rFile = & curl.exe -s -i http://localhost:9029/hello.txt
    # File that does not exist: nonexistent.txt should return 404
    $rMissing = & curl.exe -s -i http://localhost:9029/nonexistent.txt
    # Configured redirect: should return 301
    $rRedir = & curl.exe -s -i http://localhost:9029/legacy-info

    $pFile = ($rFile -match "HTTP/1\.1 200 OK")
    $pMissing = ($rMissing -match "404 Not Found")
    $pRedir = ($rRedir -match "301 Moved Permanently") -and ($rRedir -match "Location: /new-info")

    if ($pFile -and $pMissing -and $pRedir) {
        Write-Host "  PASS: Static files (200), 404s, and redirects co-exist without regression" -ForegroundColor Green
        $passCount++
    } else {
        Write-Host "  FAIL: Existing requests check failed (pFile: $pFile, pMissing: $pMissing, pRedir: $pRedir)" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host "  FAIL: Test 9 failed: $_" -ForegroundColor Red
    $failCount++
} finally {
    Stop-ServerProcess $proc
    Remove-Item "$PSScriptRoot\..\config\redirect_test9.conf" -Force -ErrorAction SilentlyContinue
}

# Cleanup temporary logs
Remove-Item "test_output.txt" -Force -ErrorAction SilentlyContinue
Remove-Item "test_error.txt" -Force -ErrorAction SilentlyContinue

Write-Host "`n=== HTTP Redirects Tests Summary: $passCount passed, $skipCount skipped, $failCount failed ===" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
if ($failCount -gt 0) {
    exit 1
}
