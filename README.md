# LocalServer

A lightweight HTTP/1.1 web server written entirely in Java using the Java NIO API.

The project is designed around a **single process and a single event-driven thread**, using `Selector`, `ServerSocketChannel`, and `SocketChannel` instead of thread-per-connection or external web server frameworks.

## Team

| Developer     | Responsibility                                                 |
| ------------- | -------------------------------------------------------------- |
| **mmoulabbi** | Core server, HTTP layer, routing, integration                  |
| **omar**      | Handlers, configuration, CGI, sessions, feature implementation |

The project is split by responsibility so both developers can work independently and minimize conflicts.

---

# Project Goals

LocalServer must provide the core functionality of an HTTP/1.1 web server:

* HTTP/1.1 request/response handling
* GET, POST and DELETE
* Multiple listening ports
* Non-blocking I/O
* Event-driven architecture
* Static file serving
* File uploads
* Directory handling
* Custom error pages
* HTTP redirects
* Chunked requests
* Request body limits
* Routing
* Server configuration
* CGI execution
* `PATH_INFO`
* Cookies
* Sessions
* Request timeouts
* Protection against malformed requests
* Protection against path traversal
* Stability under multiple requests

The implementation must remain Java-only and must not use external HTTP server frameworks such as Netty, Jetty or Grizzly.

---

# Architecture

The server follows this flow:

```text
                    ┌─────────────────────┐
                    │      Client         │
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
                    │    HttpParser       │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │    HttpRequest      │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │       Router        │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
              ▼                ▼                ▼
       StaticHandler     UploadHandler      CGIHandler
              │                │                │
              └────────────────┼────────────────┘
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

---

# Project Structure

```text
localserver/
│
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

---

# Task Split

## mmoulabbi — Core Server

`mmoulabbi` is responsible for the core architecture and integration.

### 1. NIO Server

Files:

```text
src/server/
├── HttpServer.java
└── Connection.java
```

Responsibilities:

* `Selector`
* `ServerSocketChannel`
* `SocketChannel`
* `SelectionKey`
* `OP_ACCEPT`
* `OP_READ`
* `OP_WRITE`
* Multiple listening ports
* Connection lifecycle
* Non-blocking I/O
* Single-thread event loop
* Connection timeout handling
* Preventing one client from crashing the server

### 2. HTTP Layer

Files:

```text
src/http/
├── HttpParser.java
├── HttpRequest.java
├── HttpResponse.java
└── HttpStatus.java
```

Responsibilities:

* HTTP request parsing
* Request line
* HTTP headers
* Request body
* `Content-Length`
* Chunked request parsing
* HTTP response serialization
* HTTP status codes
* Malformed request detection

### 3. Router

Files:

```text
src/router/
├── Router.java
└── Route.java
```

Responsibilities:

* Match request paths
* Select the correct handler
* Accepted HTTP methods
* Route configuration
* Redirects
* Default route
* Route priority

### 4. Integration

`mmoulabbi` is responsible for integrating Omar's modules with:

```text
HttpServer
HttpParser
Router
Handlers
Config
Sessions
```

The core event loop should remain under one clear owner to avoid conflicts.

---

# omar — Features and Server Functionality

`omar` is responsible for implementing the application-level features.

## 1. Static Files

File:

```text
src/handler/StaticHandler.java
```

Responsibilities:

* Serve HTML
* Serve text files
* Serve CSS/JS
* Basic MIME types
* Directory handling
* `index.html`
* File existence checks
* File permissions
* Path traversal protection

Examples:

```text
GET /
GET /index.html
GET /hello.txt
GET /docs/
```

---

## 2. Uploads

File:

```text
src/handler/UploadHandler.java
```

Responsibilities:

* POST uploads
* Request body handling
* Save uploaded files
* Upload directory
* Body size limits
* `413 Payload Too Large`
* `201 Created`

Example:

```text
POST /upload
```

---

## 3. DELETE

Handled as part of the handler layer.

Responsibilities:

```text
DELETE /uploads/file.txt
```

Expected behavior:

```text
204 No Content
```

or:

```text
404 Not Found
```

when the file does not exist.

---

## 4. Error Pages

File:

```text
src/handler/ErrorHandler.java
```

Responsibilities:

* 400
* 403
* 404
* 405
* 413
* 500

Use the files in:

```text
errors/
```

---

## 5. Configuration

Files:

```text
src/config/
├── Config.java
├── ConfigLoader.java
└── ServerConfig.java
```

Responsibilities:

* Host
* Ports
* Document root
* Routes
* Accepted methods
* Redirects
* Error pages
* Client body size limit
* CGI extension
* Directory listing
* Default files
* Multiple server configurations
* Default server

Configuration should be loaded from:

```text
config/server.conf
```

---

## 6. CGI

File:

```text
src/handler/CGIHandler.java
```

Responsibilities:

* Execute `.py` CGI scripts
* Use `ProcessBuilder`
* Pass the CGI script as the first argument
* Set `PATH_INFO`
* Handle CGI output
* Return the CGI response to the HTTP client

Example:

```text
GET /cgi-bin/hello.py
```

---

## 7. Cookies and Sessions

Files:

```text
src/session/
├── Cookie.java
├── Session.java
└── SessionManager.java
```

Responsibilities:

* Parse `Cookie`
* Generate session IDs
* Store sessions
* `Set-Cookie`
* Associate requests with sessions
* Session expiration/cleanup

---

# Shared Interface

Both developers should follow this basic flow:

```text
HttpRequest
      │
      ▼
    Router
      │
      ▼
   Handler
      │
      ▼
 HttpResponse
```

Handlers should not directly control the NIO event loop.

The server layer owns the connection and event loop.

The HTTP layer owns HTTP parsing and serialization.

The router decides where the request goes.

Handlers perform the actual operation.

---

# Git Workflow

Do not work directly on `main`.

Each developer creates a feature branch.

### mmoulabbi

```powershell
git checkout -b feat/router
```

Example commits:

```text
feat: implement request router
feat: integrate handlers with server
fix: handle connection timeout
```

### omar

```powershell
git checkout -b feat/static-handler
```

Example commits:

```text
feat: implement static file handler
feat: implement file uploads
feat: implement CGI handler
feat: implement server configuration
```

Before merging:

```powershell
git pull
git checkout main
git merge <feature-branch>
```

---

# Rules to Avoid Conflicts

### 1. Do not modify the same core files simultaneously

In particular:

```text
src/server/Connection.java
src/server/HttpServer.java
```

are owned by `mmoulabbi`.

If Omar needs changes there, discuss the required interface first.

### 2. Keep modules independent

Omar's handlers should receive an `HttpRequest` and return an `HttpResponse`.

Do not make handlers directly manipulate `Selector` or `SocketChannel`.

### 3. No external frameworks

Do not add:

* Netty
* Jetty
* Grizzly
* Spring
* asynchronous frameworks
* thread pools

The project must remain based on Java NIO.

### 4. No unnecessary features

Focus on mandatory requirements first.

Do not spend time on:

* HTTP/2
* HTTPS/TLS
* WebSockets
* authentication
* database
* compression
* caching
* admin dashboard
* second CGI type

until every mandatory requirement works.

---

# Development Order

## Phase 1 — Core

**mmoulabbi**

```text
NIO server
    ↓
HTTP parser
    ↓
HTTP response
    ↓
Router
```

## Phase 2 — Basic Web Server

**omar**

```text
Static files
    ↓
Error pages
    ↓
POST/uploads
    ↓
DELETE
```

## Phase 3 — Configuration

**omar + mmoulabbi**

```text
server.conf
    ↓
ConfigLoader
    ↓
ServerConfig
    ↓
Router / Server
```

## Phase 4 — Advanced Features

**omar**

```text
CGI
Cookies
Sessions
```

**mmoulabbi**

```text
NIO integration
Timeouts
Connection lifecycle
Error/crash protection
```

## Phase 5 — Integration

**Both**

Test:

```text
GET
POST
DELETE
Static files
Uploads
Chunked requests
CGI
PATH_INFO
Cookies
Sessions
Redirects
Errors
Multiple ports
Multiple servers
Directory handling
Body limits
Malformed requests
Path traversal
Timeouts
```

---

# Testing

Basic PowerShell tests:

```powershell
curl.exe http://localhost:8080/
curl.exe http://localhost:8080/index.html
curl.exe http://localhost:8080/hello.txt
curl.exe http://localhost:8080/docs/
curl.exe -X POST http://localhost:8080/
curl.exe -X DELETE http://localhost:8080/test.txt
```

The server must remain alive after:

* malformed requests
* invalid paths
* unsupported methods
* oversized bodies
* missing files
* client disconnects
* multiple simultaneous connections

---

# Definition of Done

The project is considered complete when:

* [ ] Server runs with one process
* [ ] Server uses one event-driven thread
* [ ] Multiple ports work
* [ ] HTTP/1.1 works
* [ ] GET works
* [ ] POST works
* [ ] DELETE works
* [ ] Static files work
* [ ] Uploads work
* [ ] Chunked requests work
* [ ] Error pages work
* [ ] Routing works
* [ ] Configuration works
* [ ] Multiple server configurations work
* [ ] Default server works
* [ ] CGI works
* [ ] `PATH_INFO` works
* [ ] Cookies work
* [ ] Sessions work
* [ ] Request timeouts work
* [ ] Path traversal is blocked
* [ ] Server does not crash on malformed requests
* [ ] Stress testing completed
* [ ] Memory/file-descriptor leaks checked
* [ ] README/documentation completed

---

# Main Principle

Keep the architecture simple:

```text
        NIO
         │
         ▼
       HTTP
         │
         ▼
      Router
         │
         ▼
      Handler
         │
         ▼
     Response
```

**mmoulabbi owns the core.
omar owns the features.
Both own testing and final integration.**
