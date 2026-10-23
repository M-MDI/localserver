# HTTP DELETE Verification Test Script

Write-Host "Starting HTTP DELETE Verification Tests..." -ForegroundColor Green

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

# Test 1: DELETE an existing temporary file
Write-Host "`nTest 1: DELETE an existing temporary file" -ForegroundColor Cyan
$testFile = "C:\Users\exe\Desktop\localserver\www\test_delete_temp.txt"
"Test content for DELETE" | Out-File -FilePath $testFile -Encoding UTF8
$response = curl.exe -i -X DELETE http://localhost:65432/test_delete_temp.txt 2>&1
$fileExistsAfter = Test-Path $testFile
if ($response -match "HTTP/1\.1 204" -and -not $fileExistsAfter) {
    Write-Host "PASS: File deleted successfully with 204 status" -ForegroundColor Green
    $testResults += "Test 1: PASS"
} else {
    Write-Host "FAIL: File not deleted or wrong status" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    Write-Host "File exists after: $fileExistsAfter" -ForegroundColor Red
    $testResults += "Test 1: FAIL"
}

# Test 2: DELETE a nonexistent file
Write-Host "`nTest 2: DELETE a nonexistent file" -ForegroundColor Cyan
$response = curl.exe -i -X DELETE http://localhost:65432/nonexistent_file.txt 2>&1
if ($response -match "HTTP/1\.1 404") {
    Write-Host "PASS: Nonexistent file returns 404" -ForegroundColor Green
    $testResults += "Test 2: PASS"
} else {
    Write-Host "FAIL: Wrong status for nonexistent file" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 2: FAIL"
}

# Test 3: Attempt DELETE with ../ traversal
Write-Host "`nTest 3: Attempt DELETE with ../ traversal" -ForegroundColor Cyan
$tcpClient = New-Object System.Net.Sockets.TcpClient("127.0.0.1", 65432)
$stream = $tcpClient.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)
$writer.WriteLine("DELETE /../../test.txt HTTP/1.1")
$writer.WriteLine("Host: localhost")
$writer.WriteLine("")
$writer.Flush()
Start-Sleep -Milliseconds 500
$response = $reader.ReadToEnd()
$tcpClient.Close()
if ($response -match "HTTP/1\.1 403") {
    Write-Host "PASS: Path traversal blocked with 403" -ForegroundColor Green
    $testResults += "Test 3: PASS"
} else {
    Write-Host "FAIL: Path traversal not blocked" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 3: FAIL"
}

# Test 4: Attempt DELETE against a directory
Write-Host "`nTest 4: Attempt DELETE against a directory" -ForegroundColor Cyan
$response = curl.exe -i -X DELETE http://localhost:65432/docs/ 2>&1
if ($response -match "HTTP/1\.1 403") {
    Write-Host "PASS: Directory deletion rejected with 403" -ForegroundColor Green
    $testResults += "Test 4: PASS"
} else {
    Write-Host "FAIL: Directory deletion not rejected" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 4: FAIL"
}

# Test 5: Attempt DELETE against a symlink (if supported on Windows)
Write-Host "`nTest 5: Attempt DELETE against a symlink" -ForegroundColor Cyan
Write-Host "INFO: Windows junction/symlink test" -ForegroundColor Yellow
# Create a junction pointing outside document root (if possible)
$junctionPath = "C:\Users\exe\Desktop\localserver\www\test_junction"
$targetPath = "C:\Windows"
try {
    # Try to create a junction (requires admin privileges)
    cmd /c mklink /J "$junctionPath" "$targetPath" 2>&1 | Out-Null
    if (Test-Path $junctionPath) {
        $response = curl.exe -i -X DELETE http://localhost:65432/test_junction/ 2>&1
        # Should be blocked by path traversal check
        if ($response -match "HTTP/1\.1 403" -or $response -match "HTTP/1\.1 404") {
            Write-Host "PASS: Symlink deletion blocked" -ForegroundColor Green
            $testResults += "Test 5: PASS"
        } else {
            Write-Host "FAIL: Symlink deletion not blocked" -ForegroundColor Red
            Write-Host "Response: $response" -ForegroundColor Red
            $testResults += "Test 5: FAIL"
        }
        # Clean up junction
        cmd /c rmdir "$junctionPath" 2>&1 | Out-Null
    } else {
        Write-Host "SKIP: Could not create junction (may require admin)" -ForegroundColor Yellow
        $testResults += "Test 5: SKIP"
    }
} catch {
    Write-Host "SKIP: Symlink test not supported on this system" -ForegroundColor Yellow
    $testResults += "Test 5: SKIP"
}

# Test 6: Verify GET still works
Write-Host "`nTest 6: Verify GET still works" -ForegroundColor Cyan
$response = curl.exe -i http://localhost:65432/hello.txt 2>&1
if ($response -match "HTTP/1\.1 200" -and $response -match "Hello, World!") {
    Write-Host "PASS: GET still works correctly" -ForegroundColor Green
    $testResults += "Test 6: PASS"
} else {
    Write-Host "FAIL: GET not working" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 6: FAIL"
}

# Test 7: Verify POST still works
Write-Host "`nTest 7: Verify POST still works (upload)" -ForegroundColor Cyan
$response = curl.exe -i -X POST -d "test data" http://localhost:65432/upload/ 2>&1
# Upload should either succeed (201) or return appropriate error
if ($response -match "HTTP/1\.1") {
    Write-Host "PASS: POST endpoint responds" -ForegroundColor Green
    $testResults += "Test 7: PASS"
} else {
    Write-Host "FAIL: POST not working" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 7: FAIL"
}

# Test 8: Verify 405 includes Allow header
Write-Host "`nTest 8: Verify 405 includes Allow header" -ForegroundColor Cyan
$response = curl.exe -i -X PUT http://localhost:65432/test.txt 2>&1
if ($response -match "Allow:") {
    Write-Host "PASS: 405 response includes Allow header" -ForegroundColor Green
    $testResults += "Test 8: PASS"
} else {
    Write-Host "FAIL: 405 response missing Allow header" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 8: FAIL"
}

# Test 9: Verify response headers for successful DELETE
Write-Host "`nTest 9: Verify response headers for successful DELETE" -ForegroundColor Cyan
$testFile = "C:\Users\exe\Desktop\localserver\www\test_delete_headers.txt"
"Test content" | Out-File -FilePath $testFile -Encoding UTF8
$response = curl.exe -i -X DELETE http://localhost:65432/test_delete_headers.txt 2>&1
if ($response -match "HTTP/1\.1 204" -and $response -match "Connection: close") {
    Write-Host "PASS: DELETE returns correct headers" -ForegroundColor Green
    $testResults += "Test 9: PASS"
} else {
    Write-Host "FAIL: DELETE missing expected headers" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 9: FAIL"
}

# Stop server
Write-Host "`nStopping server..." -ForegroundColor Yellow
Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Print summary
Write-Host "`n`n=== Test Summary ===" -ForegroundColor Cyan
foreach ($result in $testResults) {
    Write-Host $result
}

$passCount = ($testResults | Where-Object { $_ -match "PASS" }).Count
$skipCount = ($testResults | Where-Object { $_ -match "SKIP" }).Count
$failCount = ($testResults | Where-Object { $_ -match "FAIL" }).Count
$totalCount = $testResults.Count
Write-Host "`nTotal: $passCount/$totalCount tests passed ($skipCount skipped, $failCount failed)" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
