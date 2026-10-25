# LocalServer

LocalServer is an educational HTTP server written in Java. It demonstrates how a web server accepts TCP connections, parses HTTP requests, selects a handler, and writes HTTP responses without using a server framework.

It uses Java NIO channels and a `Selector` to handle client sockets on one event-loop thread. Static pages are served from disk; routes can also accept uploads, run CGI scripts, or redirect requests.

## What this project demonstrates

- HTTP request and response structure, headers, status codes, `Content-Length`, and chunked request bodies.
- TCP socket handling with non-blocking Java NIO and I/O multiplexing with a selector.
- Configuration parsing, virtual-host selection, route matching, and custom error pages.
- File serving, directory indexes/listings, uploads, deletion, path validation, cookies, sessions, and CGI subprocesses.
- Verification through protocol, integration, security, load, and resource-leak tests.

## How an HTTP server works

HTTP is a request/response protocol carried over a transport connection, usually TCP. A browser connects to a server address and sends a request containing a method, path, protocol version, headers, and sometimes a body. The server reads and parses the request, decides which resource or handler should answer, then returns a status line, response headers, and an optional body.

For example, a browser request might look like:

```http
GET /docs/ HTTP/1.1
Host: localhost:65432
Accept: text/html
```

The server might answer with `HTTP/1.1 200 OK`, a `Content-Type`, a `Content-Length`, and the bytes for `www/docs/index.html`.

HTTP/1.1 allows persistent connections, but this implementation sends `Connection: close` and handles one request per client connection. It closes a connection after its response has been written.

## Event-driven I/O and the selector

A blocking server can dedicate one thread to each client and wait while that client sends data. This is simple but uses a thread even when a client is idle. LocalServer instead configures its `ServerSocketChannel` and accepted `SocketChannel`s as non-blocking, then registers them with one Java NIO `Selector`.

The selector reports which registered channels are ready for an operation:

1. A listening channel registered for `OP_ACCEPT` becomes ready when a client connects.
2. An accepted client channel is registered for `OP_READ`.
3. When a complete request has been accumulated, the server prepares its response and changes the channel interest to `OP_WRITE`.
4. Writes may be partial. The connection keeps its `ByteBuffer` and continues writing when the selector reports `OP_WRITE` again.
5. Once the response buffer is empty, the connection is closed and its selection key is cancelled.

`HttpServer` owns the selector loop. It checks selected keys, dispatches accept/read/write work, and periodically checks idle connection timeouts. The loop uses a timed `select` call so it can check for connections idle for more than 30 seconds. Socket I/O errors close the affected client connection; malformed requests receive an error response where possible.

This architecture avoids a thread per socket. It does not make every operation non-blocking: file reads and CGI execution happen synchronously in the event loop. A slow disk or CGI script can therefore delay other clients.

## Request lifecycle

The main request path is:

1. `Main` loads a configuration and constructs the server.
2. `HttpServer` opens a non-blocking listener for each distinct configured port and registers each listener with the selector.
3. On `OP_ACCEPT`, the server accepts a client and creates a `Connection`.
4. `Connection.read()` accumulates bytes in an 8 KiB read buffer until the headers and expected body are complete. The accumulated request is limited to 10 MiB. The configured body-size limit is applied when headers identify the selected virtual host.
5. `HttpParser` parses the request line, headers, cookies, and body. It supports `Content-Length` and chunked transfer encoding and rejects conflicting framing headers.
6. The `Host` header selects a server configuration for the local port. The router chooses the matching route, then `StaticHandler`, `UploadHandler`, or `CGIHandler` handles the request. Redirects and `/__metrics` are handled directly by `Connection`.
7. The handler creates an `HttpResponse` or the connection creates a raw protocol error response. The response is serialized into a byte buffer.
8. The selector reports write readiness until the complete response is sent. The connection is then closed.

## Project structure

```text
localserver/
├── src/
│   ├── Main.java
│   ├── config/       # Configuration parsing and server settings
│   ├── handler/      # Static files, uploads, and CGI
│   ├── http/         # Request parser, request/response models, status and body errors
│   ├── router/       # Route definitions and longest-prefix matching
│   ├── server/       # Selector loop, connection lifecycle, and metrics
│   └── session/      # Cookie and server-side session support
├── config/           # Default and multi-port test configuration
├── errors/           # Default and test-specific error pages
├── tests/            # Audit runner and PowerShell integration suites
└── www/              # Default document root and test content/CGI scripts
```

### Main components

- **`Main`** selects the configuration file, loads the configuration, reports the selected mode, and starts `HttpServer`.
- **`ConfigLoader`** reads the legacy single-server format or multi-server blocks, validates ports, roots, names, routes, and redirects, and resolves relative paths from the project directory.
- **`ServerConfigurations`** maps ports to the server configurations that use them, selects a configuration by `Host`, and provides a default/fallback server.
- **`HttpServer`** binds each distinct port once, registers listeners and client sockets with the selector, handles readiness events, and enforces an idle timeout.
- **`Connection`** owns one client's read/write buffers and lifecycle. It selects the virtual host, routes the parsed request, attaches a session, and prepares response bytes.
- **`HttpParser`** turns bytes into an `HttpRequest`, parses headers and cookies, and decodes fixed-length or chunked bodies.
- **`Router` and `Route`** match paths on segment boundaries and prefer the longest matching prefix. A redirect wins a tie with a non-redirect route.
- **`StaticHandler`** serves files and directory indexes/listings, and deletes files when the route permits `DELETE`.
- **`UploadHandler`** accepts a raw POST body at an upload route, validates the filename and body size, refuses overwrites, stages the write in a temporary file, and moves it into place.
- **`CGIHandler`** validates the script path and extension, builds CGI environment variables, starts the configured interpreter, and translates CGI headers/body into an HTTP response.
- **`SessionManager`, `Session`, and `Cookie`** generate session identifiers, store session attributes, parse incoming cookies, and serialize `Set-Cookie` headers.
- **`ServerMetrics`** maintains process-local connection/request counters and renders the `/__metrics` Prometheus text response.

## Features and behavior

### HTTP methods and responses

The server supports `GET`, `POST`, and `DELETE` through its handlers. Unsupported methods are returned as `405 Method Not Allowed` with an `Allow` header. A successful static-file deletion returns `204 No Content`; directories cannot be deleted by the static handler. A successful upload returns `201 Created`; an existing target returns `409 Conflict`.

Responses include a status line, headers, and an optional body. Static file responses set a content type, content length, and `Connection: close`. The MIME map covers common text, web, JSON, and image extensions; unknown extensions use `application/octet-stream`.

### Request bodies and uploads

The parser accepts bodies framed by `Content-Length` or `Transfer-Encoding: chunked`. It rejects negative or malformed lengths, invalid chunk sizes, conflicting framing headers, and bodies above the selected configuration's limit. The limit accepts byte counts with optional `k`, `m`, or `g` suffixes.

The built-in `/upload` route accepts `POST /upload/<filename>`. A configured upload route can choose its own path and directory. Filenames containing traversal or path separators are rejected. Existing files are not overwritten. The handler writes to a temporary file and moves it into place to reduce the chance of leaving a partial upload after a write failure.

Uploads are treated as text by the current parser/handler path. The server is not a multipart form parser and should not be used for arbitrary binary uploads without changing the body representation to preserve bytes.

### Static files and directories

The default document root is `www/`; `config/server.conf` sets it explicitly. A directory request serves its configured index file (default `index.html`). If no index file exists, directory listing is either generated or refused, depending on `directory_listing`.

The static handler normalizes resolved paths and checks that the result remains under the configured document root. Upload and CGI handlers also validate filenames/script paths. These checks are useful defense in depth for this educational project; they are not a substitute for a production security review, especially when filesystem symlinks or untrusted users are involved.

### Routes and redirects

Routes are matched by path prefix with a boundary: `/api` matches `/api` and `/api/items`, but not `/apix`. When several routes match, the longest prefix is selected. Routes may accept a list of methods and may serve static files, accept uploads, or invoke CGI.

Redirects support `301`, `302`, `303`, `307`, and `308`. Configuration validation rejects malformed redirect paths and CR/LF header-injection attempts in targets.

### Virtual hosts and ports

Each server block may listen on one or more ports and may declare one or more `server_name` values. Multiple server blocks can share a port; LocalServer binds that socket once, then selects a configuration using the request's `Host` header. Host names are matched case-insensitively after removing a port suffix. An explicit `default_server` is used for unmatched hosts; otherwise the first valid configuration for that port is the deterministic fallback.

Duplicate port declarations inside one block and conflicting names/default selections are rejected. If a server block is invalid while another is valid, the valid block can remain available. Listener bind failures are logged per port, allowing other configured ports to start; startup fails if none can be bound.

### Error pages

The default configuration points to HTML pages under `errors/`. Error pages can be configured by status code. The server has fallback plain-text responses if a custom page cannot be read. The main configured statuses are `400`, `403`, `404`, `405`, `413`, and `500`.

### Cookies and sessions

The request parser reads the `Cookie` header. For each successfully parsed request, LocalServer gets or creates a server-side session and attaches it to `HttpRequest` via `getSession()`. New sessions are returned with a `SESSIONID` cookie using `Path=/` and `HttpOnly`.

Session IDs are generated from 32 cryptographically random bytes and encoded with URL-safe Base64. Sessions store attributes in memory and expire after 30 minutes of inactivity by default; expired sessions are periodically swept. Sessions are not persisted across server restarts.

### CGI

The built-in `/cgi-bin` route maps to `www/cgi-bin`. A script is invoked as an interpreter command followed by the script path, for example `python <script-path>`. Supported mappings are `.py` to `python`, `.sh` to `sh`, and `.pl` to `perl`.

The CGI environment includes `REQUEST_METHOD`, `SCRIPT_NAME`, `PATH_INFO`, `QUERY_STRING`, `SERVER_PROTOCOL`, `SERVER_SOFTWARE`, and `GATEWAY_INTERFACE`. `CONTENT_LENGTH` and `CONTENT_TYPE` are included when present; request headers are also exposed using `HTTP_*` names. The request body is written to the child's standard input. Standard output is captured in a temporary file, standard error is discarded, execution is limited to five seconds, and captured output is limited to 10 MiB. CGI output is parsed as text headers and a text body.

### Metrics

`GET /__metrics` returns Prometheus text format with:

- `localserver_active_connections`
- `localserver_connections_total`
- `localserver_requests_total`
- `localserver_sessions_active`
- `localserver_uptime_seconds`

The endpoint is reserved by the server and is checked before configured route dispatch.

## Configuration reference

The default file is `config/server.conf`. Start the server with another file by passing its path as the first argument. Relative document roots, route roots, and error page paths are resolved from the project working directory.

### Single-server format

```ini
port=65432
document_root=./www
max_body_size=10m
directory_listing=on
error_page 400=./errors/400.html
error_page 403=./errors/403.html
error_page 404=./errors/404.html
error_page 405=./errors/405.html
error_page 413=./errors/413.html
error_page 500=./errors/500.html
```

### Multi-server format

```text
server {
    listen 8080 8081
    server_name example.test www.example.test
    default_server
    document_root ./www
    max_body_size 10m
    directory_listing on
    index index.html
    error_page 404=./errors/404.html
    route / root
    route /api static ./www/docs GET POST
    route /upload upload ./www/uploads POST
    route /cgi-bin cgi ./www/cgi-bin GET POST
    redirect /old / 301
}
```

Supported directives:

| Directive | Purpose |
|---|---|
| `listen <port...>` | Ports for a server block; valid ports are 1–65535. |
| `server_name <name...>` | Host names used for virtual-host selection. |
| `default_server` | Selects the fallback server on its ports. |
| `document_root <path>` | Base directory for the server's static files. |
| `max_body_size <size>` | Body limit in bytes or with `k`, `m`, or `g` suffix. |
| `directory_listing on\|off` | Enables or disables generated listings when no index exists. |
| `index <filename>` | Default file served for directory requests. |
| `error_page <code>=<path>` | Custom page path for an HTTP status. |
| `route <path> <type> [root] [methods...]` | Adds a root/static, upload, or CGI route with optional accepted methods. |
| `redirect <source> <target> [status]` | Redirects a matching path; default status is 302. |

`config/multi-port-test.conf` is used by the multi-server test suite and is retained as an executable test fixture.

## Build and run

Requirements: JDK 11 or later. Python, PowerShell, and the relevant CGI interpreter are needed only for their tests or scripts.

From the repository root in PowerShell:

```powershell
javac -d bin -sourcepath src src/*.java src/config/*.java src/handler/*.java src/http/*.java src/router/*.java src/server/*.java src/session/*.java
java -cp bin Main
```

Use a custom config file with:

```powershell
java -cp bin Main path/to/server.conf
```

With the checked-in default config, LocalServer listens on port `65432` and serves `www/`. Open `http://localhost:65432/` in a browser.

## Tests and verification

Run the complete test workflow from the repository root with one command:

```powershell
powershell -ExecutionPolicy Bypass -File tests/run_all_tests.ps1
```

The runner compiles the Java project, runs `audit_runner.py`, then discovers and runs every PowerShell (`.ps1`) and shell (`.sh`) test script under `tests/`. It checks process exit codes and failure summaries and returns a non-zero exit code if a suite fails. It saves and restores `config/server.conf` around the run. If shell tests exist, the runner uses Git Bash or WSL; this repository currently has no `.sh` test scripts. Use `-SkipCompile` or `-SkipAudit` to omit those steps when needed.

The underlying suites cover:

- `audit_runner.py`: config parsing, HTTP protocol, static serving, routes, redirects, security, uploads, CGI, errors, sessions/cookies, connection lifecycle, leaks, and load.
- `redirect_test.ps1`: redirect codes, route precedence, host scoping, config validation, and header-injection protection.
- `config_routes_test.ps1`: longest-prefix routing, method limits, uploads, CGI, and DELETE through configured routes.
- `multi_server_test.ps1`: separate and shared ports, host matching, fallback selection, invalid sibling blocks, and duplicate ports.
- `cgi_test.ps1`: interpreter execution, `PATH_INFO`, POST input, exit errors, custom status, traversal, and static-serving regression.
- `directory_listing_test.ps1`: index files, generated listings, disabled listings, traversal, and headers.
- `delete_test.ps1`: successful deletion, missing files, traversal, directories, symlinks, and method headers.
- `upload_test.ps1` and `upload_edge_cases.ps1`: raw and chunked uploads, limits, conflicts, invalid input, traversal, and cleanup.
- `session_metrics_test.ps1`: cookie issuance/reuse, metrics output, and chunked CGI input.
- `run_all_tests.ps1`: compiles and orchestrates the audit and all `.ps1`/`.sh` suites.

Latest recorded verification: Java compilation succeeded; the audit runner reported 73 passed and 0 failed checks. The PowerShell suites reported 76 passed, 0 failed, and 7 skipped cases. The skips are test-client or failure-simulation limitations. The audit runner's 1,000-request load phase and the 2026-10-25 Siege run each recorded 100% availability. Siege completed 1,000 requests with no failures.

## Implementation choices and trade-offs

- **Java NIO instead of a thread per connection:** one selector and one event loop manage socket readiness. This keeps socket handling simple and avoids a client thread for every open connection.
- **Manual HTTP parser/serializer:** `HttpParser`, `HttpRequest`, and `HttpResponse` make framing and status behavior visible for learning and testing. This is not a drop-in replacement for a mature HTTP server.
- **Configuration-driven routing:** server roots, ports, host names, error pages, route methods, and redirects can be changed without editing handler code.
- **Separate handlers:** static files, uploads, and CGI have distinct classes so each request type has a focused implementation and validation path.
- **Temporary files for upload/CGI I/O:** uploads are staged before moving into place; CGI standard input/output use temporary files to avoid pipe deadlocks while keeping process I/O synchronous.
- **Single request per connection:** responses use `Connection: close`, which keeps connection state and parser behavior small. Keep-alive and pipelining are not implemented.

## Limitations and safe use

- CGI runs via `ProcessBuilder`, so each CGI request starts a child process. The server itself is one process and one event-loop thread, but the complete system is not literally one process while CGI executes.
- CGI runs synchronously in the event loop for up to five seconds, which can delay other clients.
- Bodies and CGI output are represented as text in parts of the code. Arbitrary binary upload/CGI data is not guaranteed to remain unchanged.
- A server-level idle timeout is 30 seconds. The CGI timeout is five seconds.
- The server is educational software. Do not expose it to untrusted networks or treat its path checks, parser, session handling, or error behavior as a production security boundary without an independent review.
- `tests/cgi_test.ps1` skips its query-string case because of a PowerShell client limitation. Query-string CGI behavior is covered by the audit runner and manual curl verification.
