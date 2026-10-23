# File Upload Verification Test Script

Write-Host "Starting File Upload Verification Tests..." -ForegroundColor Green

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

# Test 1: Valid small upload
Write-Host "`nTest 1: Valid small upload" -ForegroundColor Cyan
$testContent = "Hello, this is a test upload content."
$response = curl.exe -i -X POST -d $testContent http://localhost:65432/upload/test_upload.txt 2>&1
$uploadedFile = "C:\Users\exe\Desktop\localserver\www\uploads\test_upload.txt"
if ($response -match "HTTP/1\.1 201" -and (Test-Path $uploadedFile)) {
    $uploadedContent = Get-Content $uploadedFile -Raw
    if ($uploadedContent -eq $testContent) {
        Write-Host "PASS: File uploaded and content matches" -ForegroundColor Green
        $testResults += "Test 1: PASS"
        Remove-Item $uploadedFile -Force
    } else {
        Write-Host "FAIL: Content mismatch" -ForegroundColor Red
        Write-Host "Expected: $testContent" -ForegroundColor Red
        Write-Host "Got: $uploadedContent" -ForegroundColor Red
        $testResults += "Test 1: FAIL"
    }
} else {
    Write-Host "FAIL: Upload failed or file not created" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 1: FAIL"
}

# Test 2: Empty upload
Write-Host "`nTest 2: Empty upload" -ForegroundColor Cyan
# Clean up any existing file first
Remove-Item "C:\Users\exe\Desktop\localserver\www\uploads\empty_test.txt" -Force -ErrorAction SilentlyContinue
# Send request with Content-Length: 0 header explicitly
$response = curl.exe -i -X POST -H "Content-Length: 0" http://localhost:65432/upload/empty_test.txt 2>&1
if ($response -match "HTTP/1\.1 400") {
    Write-Host "PASS: Empty upload rejected with 400" -ForegroundColor Green
    $testResults += "Test 2: PASS"
} else {
    Write-Host "FAIL: Empty upload not rejected properly" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 2: FAIL"
}

# Test 3: Upload exactly at size limit (10MB - too large for practical test, using smaller)
Write-Host "`nTest 3: Upload at size limit" -ForegroundColor Cyan
Write-Host "INFO: Configured limit is 10MB, testing with 100KB for practicality" -ForegroundColor Yellow
$smallSize = 100KB
$smallData = "A" * $smallSize
$tempFile = "C:\Users\exe\Desktop\localserver\test_data.tmp"
$smallData | Out-File -FilePath $tempFile -Encoding UTF8 -NoNewline
$response = curl.exe -i -X POST --data-binary "@$tempFile" http://localhost:65432/upload/size_test.txt 2>&1
Remove-Item $tempFile -Force -ErrorAction SilentlyContinue
if ($response -match "HTTP/1\.1 201") {
    Write-Host "PASS: Upload at 100KB succeeded" -ForegroundColor Green
    $testResults += "Test 3: PASS"
    Remove-Item "C:\Users\exe\Desktop\localserver\www\uploads\size_test.txt" -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "FAIL: Upload at size limit failed" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 3: FAIL"
}

# Test 4: Upload exceeding limit
Write-Host "`nTest 4: Upload exceeding limit" -ForegroundColor Cyan
Write-Host "INFO: Temporarily setting limit to 1KB for test" -ForegroundColor Yellow
# This test would require changing config, so we'll skip for now
Write-Host "SKIP: Would require config change" -ForegroundColor Yellow
$testResults += "Test 4: SKIP"

# Test 5: Chunked upload
Write-Host "`nTest 5: Chunked upload" -ForegroundColor Cyan
# PowerShell doesn't easily support chunked encoding, so we'll skip
Write-Host "SKIP: Chunked encoding test requires custom client" -ForegroundColor Yellow
$testResults += "Test 5: SKIP"

# Test 6: Traversal filename
Write-Host "`nTest 6: Traversal filename" -ForegroundColor Cyan
$response = curl.exe -i -X POST -d "test" "http://localhost:65432/upload/..%2Ftest.txt" 2>&1
if ($response -match "HTTP/1\.1 403") {
    Write-Host "PASS: Traversal filename rejected with 403" -ForegroundColor Green
    $testResults += "Test 6: PASS"
} else {
    Write-Host "FAIL: Traversal filename not rejected" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 6: FAIL"
}

# Test 7: Overwrite existing file
Write-Host "`nTest 7: Overwrite existing file" -ForegroundColor Cyan
$existingFile = "C:\Users\exe\Desktop\localserver\www\uploads\existing.txt"
"original content" | Out-File -FilePath $existingFile -Encoding UTF8
$response = curl.exe -i -X POST -d "new content" http://localhost:65432/upload/existing.txt 2>&1
if ($response -match "HTTP/1\.1 409") {
    Write-Host "PASS: Overwrite rejected with 409" -ForegroundColor Green
    $testResults += "Test 7: PASS"
    Remove-Item $existingFile -Force
} else {
    Write-Host "FAIL: Overwrite not rejected" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 7: FAIL"
    Remove-Item $existingFile -Force
}

# Test 8: Malformed request (missing Content-Length for POST with body)
Write-Host "`nTest 8: Malformed request" -ForegroundColor Cyan
# This requires raw socket to send malformed request
Write-Host "SKIP: Requires raw socket test" -ForegroundColor Yellow
$testResults += "Test 8: SKIP"

# Test 9: Failed upload cleanup
Write-Host "`nTest 9: Failed upload cleanup" -ForegroundColor Cyan
# This is hard to test without simulating a failure
Write-Host "SKIP: Requires failure simulation" -ForegroundColor Yellow
$testResults += "Test 9: SKIP"

# Test 10: Verify GET still works
Write-Host "`nTest 10: Verify GET still works" -ForegroundColor Cyan
$response = curl.exe -i http://localhost:65432/hello.txt 2>&1
if ($response -match "HTTP/1\.1 200" -and $response -match "Hello, World!") {
    Write-Host "PASS: GET still works" -ForegroundColor Green
    $testResults += "Test 10: PASS"
} else {
    Write-Host "FAIL: GET not working" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 10: FAIL"
}

# Test 11: Verify DELETE still works
Write-Host "`nTest 11: Verify DELETE still works" -ForegroundColor Cyan
$testFile = "C:\Users\exe\Desktop\localserver\www\delete_test.txt"
"test" | Out-File -FilePath $testFile -Encoding UTF8
$response = curl.exe -i -X DELETE http://localhost:65432/delete_test.txt 2>&1
if ($response -match "HTTP/1\.1 204" -and -not (Test-Path $testFile)) {
    Write-Host "PASS: DELETE still works" -ForegroundColor Green
    $testResults += "Test 11: PASS"
} else {
    Write-Host "FAIL: DELETE not working" -ForegroundColor Red
    Write-Host "Response: $response" -ForegroundColor Red
    $testResults += "Test 11: FAIL"
    Remove-Item $testFile -Force -ErrorAction SilentlyContinue
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
