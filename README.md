# LocalServer

A high-performance, lightweight HTTP/1.1 web server written entirely in Java using the Java NIO API.

The project is designed around a **single process and a single event-driven thread**, using `Selector`, `ServerSocketChannel`, and `SocketChannel` instead of a thread-per-connection architecture or external web server frameworks.

## Developer

**Mehdi Moulabbi (`mmoulabbi`)** — Sole developer responsible for architecture, implementation, integration, testing, security, and documentation.

---

## Project Overview & Verified Capabilities

LocalServer delivers a complete, secure HTTP/1.1 implementation:

- **Single-Threaded Event Loop**: Java NIO `Selector` multiplexing all connections on non-blocking channels.
- **HTTP/1.1 Compliance**: Full parsing of request line, headers, bodies, `Content-Length`, and `Transfer-Encoding: chunked`.
- **Framing & Request Smuggling Protection**: Rejection of conflicting framing headers (both Content-Length and Transfer-Encoding present) and invalid chunk sizes (400 Bad Request).
- **HTTP Methods**: GET, POST, and DELETE supported. Unsupported methods (PUT, PATCH, OPTIONS, TRACE) safely rejected with `405 Method Not Allowed` and `Allow` headers.
- **Multiple Virtual Hosts & Ports**: Multi-server configuration blocks sharing ports or on distinct ports, with case-insensitive `Host` header routing and deterministic fallback.
- **Static File Serving**: Fast static file delivery with automatic MIME type detection and custom index files.
- **Directory Listing**: Configurable directory indexing (`on`/`off`) with HTML navigation tables and parent directory traversal safety.
- **File Uploads**: Atomic, multi-part-safe uploads with overwrite conflict protection (`409 Conflict`), traversal blocking, and automatic temp-file cleanup on aborted connections.
- **Configuration-Defined Routes**: Deterministic longest-prefix matching with strict path boundary validation (`/api` does not leak into `/apix`).
- **HTTP Redirects**: Configurable 301, 302, 303, 307, and 308 redirects with `Location` headers and header-injection protection (CRLF rejection).
- **CGI Execution**: Process-isolated execution for `.py`, `.sh`, and `.pl` scripts, `PATH_INFO`, query strings, custom status codes, 5-second execution timeout, and headerless output handling.
- **Cookies & Sessions**: RFC-compliant `Cookie` and `Set-Cookie` generation/parsing, 32-byte cryptographically secure session IDs, attributes isolation, and expiration sweeps.
- **Custom Error Pages**: Configurable HTML error pages for 400, 403, 404, 405, 413, and 500 with plain-text fallback.
- **Defensive Filesystem Security**: Multi-layer path traversal defense (`../`, `%2e%2e`, `////`, absolute paths, symlink escape checks) preventing any document root breakout.

---

## Architecture

```text
                    ┌─────────────────────┐
                    │       Client        │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │    SocketChannel    │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │       Selector      │
                    │   Single Thread     │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │     HttpParser      │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │     HttpRequest     │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │       Router        │
                    └──────────┬──────────┘
                               │
                 ┌─────────────┼─────────────┐
                 │             │             │
                 ▼             ▼             ▼
           StaticHandler UploadHandler   CGIHandler
                 │             │             │
                 └─────────────┼─────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │    HttpResponse     │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │    SocketChannel    │
                    └─────────────────────┘
```

### Key Architectural Components

- **`server.HttpServer`**: Owns the NIO selector loop, binds listening server channels, registers non-blocking client channels, and periodically sweeps idle connections (`30,000 ms` timeout).
- **`server.Connection`**: Encapsulates connection lifecycle, buffer accumulation (`MAX_BUFFER_SIZE = 10MB`), header completion verification, virtual host resolution, and response preparation.
- **`http.HttpParser`**: Parses HTTP/1.1 request-lines, case-insensitive headers, cookies, chunked streams, and body length limits.
- **`router.Router` & `router.Route`**: Evaluates routes using deterministic longest-prefix matching with strict path boundary enforcement (`cleanPath.equals(path) || cleanPath.startsWith(path + "/")`).
- **`handler.StaticHandler`**: Serves static files, resolves `index.html`, generates directory listings, and handles file `DELETE`.
- **`handler.UploadHandler`**: Handles `POST /upload/<filename>` with atomic `.tmp` file staging, overwrite conflict checks (`409`), and body size limits (`413`).
- **`handler.CGIHandler`**: Prepares standard CGI/1.1 environment variables, feeds standard input, enforces 5-second process timeouts via `ProcessBuilder`, and parses output headers.
- **`session.SessionManager` & `session.Cookie`**: Manages server-side sessions, secure tokens, expiration checks, and RFC 6265 cookie serialization.

---

## Configuration Reference

The configuration file is located at `config/server.conf` (or can be passed as a command-line argument: `java -cp bin Main <path_to_conf>`).

LocalServer supports both **Single-Server** and **Multi-Server** configuration formats.

### Multi-Server Configuration Syntax

```text
server {
    listen 8080 8081
    server_name example.com www.example.com
    default_server
    document_root ./www
    max_body_size 10m
    directory_listing on
    index index.html

    # Custom Error Pages
    error_page 400=./errors/400.html
    error_page 403=./errors/403.html
    error_page 404=./errors/404.html
    error_page 405=./errors/405.html
    error_page 413=./errors/413.html
    error_page 500=./errors/500.html

    # Configuration-Defined Routes
    route / root
    route /api static ./www/docs
    route /upload upload ./www/uploads POST
    route /cgi-bin cgi ./www/cgi-bin

    # HTTP Redirects
    redirect /old-home / 301
    redirect /temp-docs /api 302
}

server {
    listen 8080
    server_name api.example.com
    document_root ./www/api
    max_body_size 2m
    directory_listing off

    route / root
    redirect /legacy /api/v1 308
}
```

### Directive Reference

| Directive | Scope | Description |
|---|---|---|
| `listen <port...>` | Server | One or more listening TCP ports (1–65535). |
| `server_name <name...>` | Server | Virtual host names matched against the request `Host` header. |
| `default_server` | Server | Marks this block as the default server for its listening ports. |
| `document_root <path>` | Server | Base directory for files and relative route paths. |
| `max_body_size <size>` | Server | Max request body size (supports `k`, `m`, `g` suffixes, e.g. `10m`). |
| `directory_listing <on/off>` | Server | Enables or disables directory browsing when no index file exists. |
| `index <filename>` | Server | Default file served for directory requests (default: `index.html`). |
| `error_page <code>=<path>` | Server | Custom HTML file path for specific HTTP error status codes. |
| `route <path> <action/dir> [methods...]` | Server | Configures a route. Actions: `root`, `static`, `upload`, `cgi`, or a directory path. |
| `redirect <source> <target> [code]` | Server | Configures an HTTP redirect. Supported codes: `301`, `302`, `303`, `307`, `308`. |

---

## Build and Run

### Prerequisites

- Java Development Kit (JDK 11 or later)
- Python 3.8+ (for CGI and audit test suite)
- PowerShell (on Windows)

### Compilation

```powershell
# Compile all source packages cleanly into bin/
javac -d bin -sourcepath src src/*.java src/config/*.java src/handler/*.java src/http/*.java src/router/*.java src/server/*.java src/session/*.java
```

### Running the Server

```powershell
# Run with default config/server.conf
java -cp bin Main

# Run with a custom configuration file
java -cp bin Main path/to/custom.conf
```

---

## Testing & Verification

### Running the Full Regression Suites

```powershell
# 1. Comprehensive Protocol, Config, Security & Leak Audit (Python)
python tests/audit_runner.py

# 2. Redirect Suite
powershell -ExecutionPolicy Bypass -File tests/redirect_test.ps1

# 3. Route Suite
powershell -ExecutionPolicy Bypass -File tests/config_routes_test.ps1

# 4. Multi-Server Virtual Hosting Suite
powershell -ExecutionPolicy Bypass -File tests/multi_server_test.ps1

# 5. CGI Verification Suite
powershell -ExecutionPolicy Bypass -File tests/cgi_test.ps1

# 6. Directory Listing Suite
powershell -ExecutionPolicy Bypass -File tests/directory_listing_test.ps1

# 7. DELETE Method Suite
powershell -ExecutionPolicy Bypass -File tests/delete_test.ps1

# 8. File Upload Suite
powershell -ExecutionPolicy Bypass -File tests/upload_test.ps1

# 9. Upload Edge Cases Suite
powershell -ExecutionPolicy Bypass -File tests/upload_edge_cases.ps1
```

---

## Reliability, Load & Resource Leak Results

### Stress & Concurrency Benchmark

Because `siege` is not available on Windows test platforms, the server was benchmarked using an equivalent multi-threaded concurrent load harness (`tests/audit_runner.py` Phase 12):

- **Concurrency**: 10 concurrent worker threads
- **Total Transactions**: 1,000 requests
- **Successful Transactions**: 1,000
- **Failed Transactions**: 0
- **Availability**: **100.00%** (exceeds the 99.5% project target)
- **Throughput**: **1,423.6 requests/second**
- **Latency**:
  - Min: `0.0 ms`
  - Avg: `6.5 ms`
  - Max: `29.0 ms`

### Resource Leak Audit (2,000 Repeated Requests)

A 2,000-request multi-endpoint workload (GET static, GET directory, GET redirect, GET 404) was executed to test for socket, file descriptor, and memory leaks:

- **Errors / Dropped Requests**: 0
- **Handle Count**: 329 → 332 (+3 transient handles, no leak)
- **Memory (Working Set)**: 51.7 MB → 71.4 MB (stable JVM heap allocation)
- **Lingering Sockets**: 2 (decaying `TIME_WAIT` TCP states)
- **Temporary Upload Files**: 0 leaked (atomic cleanup verified)
- **Child Processes**: 0 orphan processes

---

## Definition of Done

- [x] Server runs in one process with a single event-driven thread
- [x] Multiple listening ports work
- [x] HTTP/1.1 parsing and response serialization work
- [x] GET, POST, and DELETE work correctly
- [x] Static file serving works
- [x] File uploads work safely (atomic writes, 409 conflict, traversal protected)
- [x] Chunked requests work
- [x] Request body limits are enforced (413 Payload Too Large)
- [x] Custom error pages work (400, 403, 404, 405, 413, 500)
- [x] Routing and redirects work (301, 302, 303, 307, 308)
- [x] Configuration loading works
- [x] Multiple server configurations and default-server selection work
- [x] CGI execution, query strings, and `PATH_INFO` work
- [x] Cookies and sessions work
- [x] Directory handling and listing meet the specification
- [x] Request timeouts work (30s idle connection timeout, 5s CGI timeout)
- [x] Path traversal is blocked across all handlers
- [x] Malformed requests do not crash the server
- [x] Stress testing is completed and results are recorded (100% availability, 1423.6 req/s)
- [x] Memory and file-descriptor leaks are checked (clean)
- [x] README and configuration documentation match the actual implementation

---

## Known Limitations

1. **CGI Synchronous Process Wait**: As inherent to a single-threaded architecture using `ProcessBuilder.waitFor(timeout)`, executing CGI scripts blocks the event loop for the duration of the script execution (up to the 5-second timeout).
2. **Text-Oriented CGI I/O**: CGI script standard input and output are parsed as UTF-8 encoded text streams; binary CGI output (e.g. dynamic binary image streaming) may undergo character encoding conversions.
3. **PowerShell TcpClient Test Limitation**: In `tests/cgi_test.ps1`, Test 3 is skipped due to a PowerShell `TcpClient` stream writing limitation with query strings; actual query string functionality is verified via `curl.exe` and `tests/audit_runner.py`.
