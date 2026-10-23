# Directory Listing Test Script

Write-Host "Starting Directory Listing Tests..." -ForegroundColor Green

# Kill any existing server on port 65432
$process = Get-NetTCPConnection -LocalPort 65432 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess
if ($process) {
    Stop-Process -Id $process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

# Start server
Write-Host "Starting server..." -ForegroundColor Yellow
$serverProcess = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -WorkingDirectory "C:\Users\exe\Desktop\localserver" -NoNewWindow -PassThru
Start-Sleep -Seconds 2

$testResults = @()

# Test 1: Existing file should return 200
Write-Host "`nTest 1: Existing file (hello.txt)" -ForegroundColor Cyan
$response = curl.exe -i http://localhost:65432/hello.txt 2>&1
if ($response -match "HTTP/1\.1 200 OK" -and $response -match "Content-Type: text/plain") {
    Write-Host "PASS: File returned 200 with correct Content-Type" -ForegroundColor Green
    $testResults += "Test 1: PASS"
} else {
    Write-Host "FAIL: File did not return expected response" -ForegroundColor Red
    $testResults += "Test 1: FAIL"
}

# Test 2: Directory with index file should serve index.html
Write-Host "`nTest 2: Directory with index file (docs/)" -ForegroundColor Cyan
$response = curl.exe -i http://localhost:65432/docs/ 2>&1
if ($response -match "HTTP/1\.1 200 OK" -and $response -match "Documentation") {
    Write-Host "PASS: Index file served correctly" -ForegroundColor Green
    $testResults += "Test 2: PASS"
} else {
    Write-Host "FAIL: Index file not served" -ForegroundColor Red
    $testResults += "Test 2: FAIL"
}

# Test 3: Directory without index + listing enabled = HTML listing
Write-Host "`nTest 3: Directory without index + listing enabled (testdir/)" -ForegroundColor Cyan
$response = curl.exe -i http://localhost:65432/testdir/ 2>&1
if ($response -match "HTTP/1\.1 200 OK" -and $response -match "Index of testdir" -and $response -match "file with spaces.txt") {
    Write-Host "PASS: Directory listing generated with special characters" -ForegroundColor Green
    $testResults += "Test 3: PASS"
} else {
    Write-Host "FAIL: Directory listing not generated correctly" -ForegroundColor Red
    $testResults += "Test 3: FAIL"
}

# Test 4: Path traversal attempt should be blocked
Write-Host "`nTest 4: Path traversal attempt (../../Windows/)" -ForegroundColor Cyan
$response = curl.exe -i "http://localhost:65432/../../Windows/" 2>&1
if ($response -match "HTTP/1\.1 403 Forbidden" -or $response -match "HTTP/1\.1 404 Not Found") {
    Write-Host "PASS: Path traversal blocked" -ForegroundColor Green
    $testResults += "Test 4: PASS"
} else {
    Write-Host "FAIL: Path traversal not blocked" -ForegroundColor Red
    $testResults += "Test 4: FAIL"
}

# Test 5: Another path traversal attempt (using raw socket to avoid curl normalization)
Write-Host "`nTest 5: Path traversal attempt with raw HTTP (../)" -ForegroundColor Cyan
$tcpClient = New-Object System.Net.Sockets.TcpClient("127.0.0.1", 65432)
$stream = $tcpClient.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)
$writer.WriteLine("GET /../ HTTP/1.1")
$writer.WriteLine("Host: localhost")
$writer.WriteLine("")
$writer.Flush()
Start-Sleep -Milliseconds 500
$response = $reader.ReadToEnd()
$tcpClient.Close()
if ($response -match "HTTP/1\.1 403 Forbidden") {
    Write-Host "PASS: Path traversal blocked with raw HTTP" -ForegroundColor Green
    $testResults += "Test 5: PASS"
} else {
    Write-Host "FAIL: Path traversal not blocked with raw HTTP" -ForegroundColor Red
    $testResults += "Test 5: FAIL"
}

# Stop server
Write-Host "`nStopping server..." -ForegroundColor Yellow
Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Now test with directory_listing=off
Write-Host "`nTesting with directory_listing=off..." -ForegroundColor Yellow

# Update config
$configPath = "C:\Users\exe\Desktop\localserver\config\server.conf"
$configContent = Get-Content $configPath
$configContent = $configContent -replace "directory_listing=on", "directory_listing=off"
$configContent | Set-Content $configPath

# Start server
$serverProcess = Start-Process -FilePath "java" -ArgumentList "-cp bin Main" -WorkingDirectory "C:\Users\exe\Desktop\localserver" -NoNewWindow -PassThru
Start-Sleep -Seconds 2

# Test 6: Directory without index + listing disabled = 403
Write-Host "`nTest 6: Directory without index + listing disabled (testdir/)" -ForegroundColor Cyan
$response = curl.exe -i http://localhost:65432/testdir/ 2>&1
if ($response -match "HTTP/1\.1 403 Forbidden") {
    Write-Host "PASS: Directory listing disabled, returned 403" -ForegroundColor Green
    $testResults += "Test 6: PASS"
} else {
    Write-Host "FAIL: Did not return 403 when listing disabled" -ForegroundColor Red
    $testResults += "Test 6: FAIL"
}

# Stop server
Write-Host "`nStopping server..." -ForegroundColor Yellow
Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Restore config
$configContent = $configContent -replace "directory_listing=off", "directory_listing=on"
$configContent | Set-Content $configPath

# Print summary
Write-Host "`n`n=== Test Summary ===" -ForegroundColor Cyan
foreach ($result in $testResults) {
    Write-Host $result
}

$passCount = ($testResults | Where-Object { $_ -match "PASS" }).Count
$totalCount = $testResults.Count
Write-Host "`nTotal: $passCount/$totalCount tests passed" -ForegroundColor $(if ($passCount -eq $totalCount) { "Green" } else { "Yellow" })
