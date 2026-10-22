# LocalServer

A lightweight HTTP/1.1 web server written entirely in Java using the Java NIO API.

The project is designed around a **single process and a single event-driven thread**, using `Selector`, `ServerSocketChannel`, and `SocketChannel` instead of a thread-per-connection architecture or external web server frameworks.

## Developer

**Mehdi Moulabbi (`mmoulabbi`)** — Sole developer responsible for architecture, implementation, integration, testing, security, and documentation.

---

## Project Goals

LocalServer aims to provide the core functionality of an HTTP/1.1 web server:

- HTTP/1.1 request and response handling
- GET, POST, and DELETE methods
- Multiple listening ports
- Non-blocking I/O
- Event-driven architecture
- Static file serving
- File uploads
- Directory handling and listing
- Custom error pages
- HTTP redirects
- Chunked request parsing
- Request body size limits
- Request routing
- Server configuration
- CGI execution
- `PATH_INFO` support
- Cookies and sessions
- Request timeouts
- Protection against malformed requests
- Protection against path traversal
- Stability under multiple requests

The implementation must remain Java-based and must not use external HTTP server frameworks such as Netty, Jetty, or Grizzly.

## Architecture

The server follows this request-processing flow:

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
                ┌──────────────┼──────────────┐
                │              │              │
                ▼              ▼              ▼
          StaticHandler   UploadHandler   CGIHandler
                │              │              │
                └──────────────┼──────────────┘
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

### Architectural principles

- `HttpServer` owns the NIO event loop and listening sockets.
- `Connection` manages individual client connections and their lifecycle.
- `HttpParser` parses incoming HTTP requests.
- `HttpRequest` represents a parsed request.
- `Router` selects the appropriate handler.
- Handlers implement application-level behavior.
- `HttpResponse` represents the outgoing HTTP response.
- The server layer remains responsible for connection state and network I/O.

The design avoids unnecessary abstractions and external dependencies.

---

## Project Structure

```text
localserver/
├── src/
│   ├── Main.java
│   │
│   ├── server/
│   │   ├── HttpServer.java
│   │   └── Connection.java
│   │
│   ├── http/
│   │   ├── HttpParser.java
│   │   ├── HttpRequest.java
│   │   ├── HttpResponse.java
│   │   └── HttpStatus.java
│   │
│   ├── router/
│   │   ├── Router.java
│   │   └── Route.java
│   │
│   ├── config/
│   │   ├── Config.java
│   │   ├── ConfigLoader.java
│   │   └── ServerConfig.java
│   │
│   ├── handler/
│   │   ├── StaticHandler.java
│   │   ├── UploadHandler.java
│   │   ├── CGIHandler.java
│   │   └── ErrorHandler.java
│   │
│   └── session/
│       ├── Cookie.java
│       ├── Session.java
│       └── SessionManager.java
│
├── config/
│   └── server.conf
│
├── www/
│   ├── index.html
│   ├── hello.txt
│   ├── uploads/
│   ├── docs/
│   │   └── index.html
│   └── cgi-bin/
│       └── hello.py
│
├── errors/
│   ├── 400.html
│   ├── 403.html
│   ├── 404.html
│   ├── 405.html
│   ├── 413.html
│   └── 500.html
│
├── tests/
│   └── test.ps1
│
├── bin/
├── README.md
└── .gitignore
```

This tree describes the intended project organization; individual features must be verified against the actual implementation.

---

## Core Components

### 1. NIO Server

Files: `src/server/HttpServer.java`, `src/server/Connection.java`

Responsibilities:

- `Selector`-based event handling
- `ServerSocketChannel` and `SocketChannel` management
- `SelectionKey` operations: `OP_ACCEPT`, `OP_READ`, and `OP_WRITE`
- Multiple listening ports
- Non-blocking client connections
- Single-threaded event loop
- Connection lifecycle and timeout handling
- Graceful handling of client disconnects and network errors

### 2. HTTP Layer

Directory: `src/http/`

Responsibilities:

- HTTP request-line parsing
- Header parsing and validation
- Request-body handling
- `Content-Length` support
- Chunked transfer decoding
- HTTP response serialization
- HTTP status codes
- Malformed-request detection
- Request body size enforcement

### 3. Router

Directory: `src/router/`

Responsibilities:

- Request-path matching
- Handler selection
- HTTP method validation
- Route configuration
- Redirects
- Default route handling
- Route-priority rules

### 4. Static File Serving

File: `src/handler/StaticHandler.java`

Responsibilities:

- Serve HTML, text, CSS, JavaScript, JSON, and supported image formats
- Set appropriate MIME types
- Resolve default files such as `index.html`
- Handle directory requests according to configuration
- Check file existence and access permissions
- Prevent path traversal outside the document root

Example requests:

```http
GET /
GET /index.html
GET /hello.txt
GET /docs/
```

### 5. File Uploads

File: `src/handler/UploadHandler.java`

Responsibilities:

- Handle POST uploads
- Validate upload destinations
- Enforce request-body size limits
- Prevent path traversal and unintended overwrites
- Handle upload failures safely
- Return appropriate HTTP status codes, including `201 Created` when applicable

Example:

```http
POST /upload
```

The supported upload format and route must be documented according to the actual implementation.

### 6. HTTP DELETE

DELETE handling should:

- Delete permitted files inside the document root
- Reject directory deletion
- Prevent path traversal and symbolic-link escapes
- Return `204 No Content` after successful deletion
- Return appropriate errors for missing or forbidden resources
- Respect route method restrictions

Example:

```http
DELETE /uploads/file.txt
```

### 7. Error Handling

File: `src/handler/ErrorHandler.java`

Support custom error pages for:

- `400 Bad Request`
- `403 Forbidden`
- `404 Not Found`
- `405 Method Not Allowed`
- `413 Payload Too Large`
- `500 Internal Server Error`

Error handling must include safe fallback responses if a custom page is missing or inaccessible.

### 8. Server Configuration

Directory: `src/config/`

Configuration file: `config/server.conf`

Intended configuration responsibilities:

- Host and port settings
- Document roots
- Routes and accepted methods
- Redirects
- Error-page paths
- Request body size limits
- CGI extensions and execution settings
- Directory-listing behavior
- Default files
- Multiple server configurations
- Default-server selection

Configuration parsing must validate invalid or missing values and report clear startup errors.

### 9. CGI

File: `src/handler/CGIHandler.java`

Responsibilities:

- Execute supported CGI scripts using `ProcessBuilder`
- Pass the script path as the first argument to the interpreter
- Set CGI environment variables
- Support `PATH_INFO`
- Pass request bodies to the script's standard input when applicable
- Parse CGI output and response headers
- Handle process failures and timeouts
- Prevent execution of scripts outside permitted directories

Example:

```http
GET /cgi-bin/hello.py
```

CGI execution must not introduce arbitrary shell-command execution. Its effect on the single-threaded event loop must also be tested.

### 10. Cookies and Sessions

Directory: `src/session/`

Responsibilities:

- Parse incoming `Cookie` headers
- Generate `Set-Cookie` response headers
- Generate unpredictable session identifiers
- Associate requests with server-side sessions
- Store and retrieve session attributes
- Expire and clean up inactive sessions
- Prevent accidental session sharing between clients

Session identifiers should be the only session data stored in the session cookie; session attributes remain server-side.

---

## Development Workflow

The project is developed and integrated by one developer. Work should be divided into small, independently verifiable tasks.

For each task:

1. Inspect the existing implementation before editing.
2. Identify the smallest necessary change.
3. Implement only the assigned feature.
4. Compile the entire project.
5. Run focused tests and relevant regression tests.
6. Review changed files and any remaining limitations.
7. Commit the verified change.
8. Move to the next task only after verification.

### Git workflow

Use a separate feature branch for substantial work when appropriate.

```powershell
git checkout -b feat/feature-name
```

Review the working tree:

```powershell
git status
git diff
```

Commit verified changes with descriptive messages:

```powershell
git add src
git commit -m "feat: describe implemented feature"
```

Keep commits focused. Avoid committing unrelated changes or claiming functionality is complete without test evidence.

---

## Implementation Roadmap

### Phase 1 — Core server

- NIO event loop
- Connection lifecycle
- HTTP parsing and response serialization
- Routing
- Multiple listening ports
- Timeouts and malformed-request handling

### Phase 2 — HTTP functionality

- Static files
- GET, POST, and DELETE
- Request-body size enforcement
- Chunked requests
- Custom error pages
- File uploads

### Phase 3 — Configuration

- Configuration loading
- Host and port settings
- Document roots
- Route definitions
- Error pages and body limits
- Redirects and default files
- Multiple server configurations and default-server selection

### Phase 4 — Advanced functionality

- CGI execution
- `PATH_INFO`
- Cookies
- Sessions
- Directory listing

### Phase 5 — Integration and verification

- Regression testing
- Malformed-request testing
- Path traversal and filesystem security checks
- Multiple-client testing
- Stress testing
- Memory and file-descriptor leak checks
- Documentation and final requirements audit

The roadmap is a planning guide, not a claim that all listed features are already implemented.

---

## Testing

Basic PowerShell requests:

```powershell
curl.exe -i http://localhost:8080/
curl.exe -i http://localhost:8080/index.html
curl.exe -i http://localhost:8080/hello.txt
curl.exe -i http://localhost:8080/docs/
curl.exe -i -X POST http://localhost:8080/
curl.exe -i -X DELETE http://localhost:8080/test.txt
```

The automated test suite should cover:

- Valid and malformed HTTP requests
- Supported and unsupported methods
- Missing files and forbidden paths
- Content-Length and chunked request bodies
- Oversized requests
- Upload success and failure cases
- DELETE behavior
- Custom error pages and fallback responses
- CGI output, `PATH_INFO`, and process failures
- Cookie parsing and session expiration
- Multiple ports and server configurations
- Connection timeouts and client disconnects
- Concurrent clients and server responsiveness
- Filesystem path traversal and symbolic-link attacks

Run the project's required stress test using the expected target and test environment:

```text
siege -b [IP]:[PORT]
```

The project targets **99.5% availability** under the specified evaluation conditions. The result must be measured and recorded rather than assumed.

---

## Definition of Done

- [ ] Server runs in one process with a single event-driven thread
- [ ] Multiple listening ports work
- [ ] HTTP/1.1 parsing and response serialization work
- [ ] GET, POST, and DELETE work correctly
- [ ] Static file serving works
- [ ] File uploads work safely
- [ ] Chunked requests work
- [ ] Request body limits are enforced
- [ ] Custom error pages work
- [ ] Routing and redirects work
- [ ] Configuration loading works
- [ ] Multiple server configurations and default-server selection work
- [ ] CGI execution and `PATH_INFO` work
- [ ] Cookies and sessions work
- [ ] Directory handling and listing meet the specification
- [ ] Request timeouts work
- [ ] Path traversal is blocked
- [ ] Malformed requests do not crash the server
- [ ] Stress testing is completed and results are recorded
- [ ] Memory and file-descriptor leaks are checked
- [ ] README and configuration documentation match the actual implementation

## Main Principle

Keep the architecture simple and responsibilities clear:

```text
NIO Server
    |
    v
 HTTP Layer
    |
    v
   Router
    |
    v
  Handler
    |
    v
 HttpResponse
```

**One developer. One coherent architecture. Small changes, verified tests, and focused commits.**

Prioritize mandatory requirements and reliability before optional features.
