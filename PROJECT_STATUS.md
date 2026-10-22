# LocalServer Project Status

## Overview
LocalServer is a lightweight HTTP/1.1 web server written in Java using NIO (Non-blocking I/O). It uses a single process with a single event-driven thread architecture.

---

## How It Works

### Architecture Flow
```
Client → SocketChannel → Selector (Single Thread) → HttpParser → HttpRequest
  → Router → Handler → HttpResponse → SocketChannel → Client
```

### Key Components

1. **NIO Server** (`src/server/HttpServer.java`)
   - Uses `Selector` for event-driven I/O
   - `ServerSocketChannel` for accepting connections
   - `SocketChannel` for client communication
   - Single-threaded event loop with timeout checking
   - Supports multiple listening ports

2. **Connection Management** (`src/server/Connection.java`)
   - Handles read/write operations
   - Tracks last active time for timeout enforcement (30s default)
   - Integrates HTTP parsing, routing, and handler logic
   - Generates error responses and redirects

3. **HTTP Layer** (`src/http/`)
   - `HttpParser`: Parses HTTP requests (request line, headers, body)
   - `HttpRequest`: Stores parsed request data
   - `HttpResponse`: Serializes HTTP responses
   - `HttpStatus`: HTTP status code definitions

4. **Router** (`src/router/`)
   - `Router`: Matches requests to routes
   - `Route`: Defines path, document root, accepted methods, redirects
   - Supports default route and path-based matching

5. **Handlers** (`src/handler/`)
   - `StaticHandler`: Serves static files with MIME type detection
   - `UploadHandler`: **[NOT IMPLEMENTED]**
   - `CGIHandler`: **[NOT IMPLEMENTED]**
   - `ErrorHandler`: **[NOT IMPLEMENTED]**

6. **Sessions** (`src/session/`)
   - `Cookie`, `Session`, `SessionManager`: **[NOT IMPLEMENTED]**

7. **Configuration** (`src/config/`)
   - `ConfigLoader`: **[NOT IMPLEMENTED]**
   - Configuration is currently hardcoded in `Connection.java`

---

## What's Working ✅

### Core Server
- ✅ NIO-based server with `Selector`, `ServerSocketChannel`, `SocketChannel`
- ✅ Single-threaded event-driven architecture
- ✅ Multiple listening ports support
- ✅ Connection lifecycle management
- ✅ Connection timeout handling (30 seconds)
- ✅ Non-blocking I/O operations

### HTTP Layer
- ✅ HTTP/1.1 request parsing
- ✅ Request line parsing (method, path, version)
- ✅ HTTP header parsing
- ✅ Request body parsing (Content-Length)
- ✅ Chunked transfer encoding parsing
- ✅ HTTP response serialization
- ✅ HTTP status codes

### Routing
- ✅ Path-based route matching
- ✅ Default route fallback
- ✅ Route priority (longest match wins)
- ✅ HTTP method validation
- ✅ Redirect support (hardcoded in Connection)

### Static File Serving
- ✅ Serve HTML, CSS, JS, JSON, TXT files
- ✅ Serve images (PNG, JPG, GIF)
- ✅ MIME type detection
- ✅ Directory handling with `index.html`
- ✅ File existence checks
- ✅ File permission checks
- ✅ **Path traversal protection** (blocks `..` and normalizes paths)

### Error Handling
- ✅ 400 Bad Request (malformed requests)
- ✅ 403 Forbidden (path traversal, permissions)
- ✅ 404 Not Found (missing files)
- ✅ 405 Method Not Allowed (unsupported methods)
- ✅ 500 Internal Server Error (I/O errors)
- ✅ Server crash protection (try-catch blocks)
- ✅ Connection close on errors

### Methods
- ✅ GET requests
- ⚠️ POST (parsed but no handler implemented)
- ❌ DELETE (not implemented)

---

## What's Missing ❌

### Handlers
- ❌ **UploadHandler** - File upload functionality (empty class)
- ❌ **CGIHandler** - CGI script execution (empty class)
- ❌ **ErrorHandler** - Custom error page serving (empty class)
  - Error page HTML files exist in `errors/` but are not used
  - Currently returns plain text error responses

### Configuration
- ❌ **ConfigLoader** - Configuration file parsing (empty class)
- ❌ `server.conf` is empty (no configuration loading)
- ❌ No configurable host, ports, document root
- ❌ No configurable routes, redirects, error pages
- ❌ No configurable body size limits
- ❌ No configurable CGI extensions
- ❌ No multiple server configurations
- ❌ No default server configuration

### Sessions & Cookies
- ❌ **Cookie** class (empty)
- ❌ **Session** class (empty)
- ❌ **SessionManager** class (empty)
- ❌ No cookie parsing
- ❌ No session ID generation
- ❌ No session storage
- ❌ No Set-Cookie header support
- ❌ No session expiration/cleanup

### HTTP Methods
- ❌ **DELETE** method not implemented
- ⚠️ POST parsed but no upload handler to process it

### Advanced Features
- ❌ **PATH_INFO** support for CGI
- ❌ Request body size limits (can be specified but not enforced)
- ❌ Directory listing (returns 403 for directories without index.html)
- ❌ Custom error pages (HTML files exist but not served)
- ❌ Configurable redirects (currently hardcoded)

### Testing
- ❌ **test.ps1** is empty
- ❌ No automated tests
- ❌ No stress testing
- ❌ No memory leak detection
- ❌ No file descriptor leak detection

---

## Current Limitations

1. **Hardcoded Configuration**
   - Port is hardcoded in `Main.java` (65432)
   - Document root is hardcoded in `Connection.java` (`./www`)
   - Routes are hardcoded in `Connection.java`

2. **No File Uploads**
   - POST requests are parsed but no handler saves uploaded files
   - No upload directory management
   - No 413 Payload Too Large response for oversized uploads

3. **No CGI Support**
   - Cannot execute Python scripts
   - No ProcessBuilder integration
   - No PATH_INFO environment variable

4. **No Session Management**
   - No cookie handling
   - No session storage
   - No session-based features

5. **Basic Error Pages**
   - Custom HTML error pages exist but are not served
   - All errors return plain text responses

6. **No DELETE Method**
   - Cannot delete files via HTTP DELETE

---

## File Status Summary

| File | Status | Notes |
|------|--------|-------|
| `src/server/HttpServer.java` | ✅ Complete | NIO server, selector, timeouts |
| `src/server/Connection.java` | ✅ Working | Read/write, routing, basic errors |
| `src/http/HttpParser.java` | ✅ Complete | Request parsing, chunked encoding |
| `src/http/HttpRequest.java` | ✅ Complete | Request data model |
| `src/http/HttpResponse.java` | ✅ Complete | Response serialization |
| `src/http/HttpStatus.java` | ✅ Complete | Status codes |
| `src/router/Router.java` | ✅ Complete | Route matching |
| `src/router/Route.java` | ✅ Complete | Route model |
| `src/handler/StaticHandler.java` | ✅ Complete | Static file serving |
| `src/handler/UploadHandler.java` | ❌ Empty | Needs implementation |
| `src/handler/CGIHandler.java` | ❌ Empty | Needs implementation |
| `src/handler/ErrorHandler.java` | ❌ Empty | Needs implementation |
| `src/session/Cookie.java` | ❌ Empty | Needs implementation |
| `src/session/Session.java` | ❌ Empty | Needs implementation |
| `src/session/SessionManager.java` | ❌ Empty | Needs implementation |
| `src/config/ConfigLoader.java` | ❌ Empty | Needs implementation |
| `config/server.conf` | ❌ Empty | Needs configuration |
| `tests/test.ps1` | ❌ Empty | Needs test cases |

---

## Definition of Done Progress

Based on the README requirements:

- [x] Server runs with one process
- [x] Server uses one event-driven thread
- [x] Multiple ports work
- [x] HTTP/1.1 works
- [x] GET works
- ⚠️ POST works (parsed but no handler)
- [ ] DELETE works
- [x] Static files work
- [ ] Uploads work
- [x] Chunked requests work (parsed)
- [ ] Error pages work (HTML files not served)
- [x] Routing works
- [ ] Configuration works
- [ ] Multiple server configurations work
- [ ] Default server works
- [ ] CGI works
- [ ] PATH_INFO works
- [ ] Cookies work
- [ ] Sessions work
- [x] Request timeouts work
- [x] Path traversal is blocked
- [x] Server does not crash on malformed requests
- [ ] Stress testing completed
- [ ] Memory/file-descriptor leaks checked
- [x] README/documentation completed

**Progress: ~45% complete**

---

## Next Steps (Priority Order)

### High Priority
1. Implement `UploadHandler` for POST file uploads
2. Implement `ConfigLoader` to load `server.conf`
3. Implement `ErrorHandler` to serve custom error pages
4. Implement DELETE method support

### Medium Priority
5. Implement `CGIHandler` for Python script execution
6. Implement PATH_INFO support
7. Implement session management (Cookie, Session, SessionManager)
8. Add request body size limit enforcement

### Low Priority
9. Add directory listing option
10. Create comprehensive test suite in `test.ps1`
11. Perform stress testing
12. Check for memory/file descriptor leaks

---

## How to Run

```bash
# Compile
javac -d bin src/**/*.java

# Run
java -cp bin Main
```

Server will listen on port **65432** by default.

Test with:
```bash
curl http://localhost:65432/
curl http://localhost:65432/index.html
curl http://localhost:65432/hello.txt
```

---

## Notes

- The server is stable for basic GET requests and static file serving
- Path traversal protection is implemented and working
- Connection timeout prevents idle connections from hanging
- Malformed requests are caught and return 400 without crashing the server
- The architecture is clean and follows the planned separation of concerns
- Configuration, CGI, sessions, and uploads are the main missing pieces
