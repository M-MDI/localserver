package server;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import http.HttpParser;
import http.HttpRequest;
import http.HttpResponse;
import http.PayloadTooLargeException;
import handler.StaticHandler;
import handler.UploadHandler;
import handler.CGIHandler;
import router.Route;
import router.Router;
import session.SessionManager;
import config.ServerConfig;

public class Connection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private long lastActiveTime;
    private final String documentRoot;
    private final ServerConfig config;
    private static final SessionManager sessionManager = new SessionManager();
    private ByteArrayOutputStream accumulatedData;
    private boolean requestComplete;
    private int expectedBodySize;
    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10MB max accumulation

    public Connection(SocketChannel channel, String documentRoot, ServerConfig config) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = null;
        this.lastActiveTime = System.currentTimeMillis();
        this.documentRoot = documentRoot;
        this.config = config;
        this.accumulatedData = new ByteArrayOutputStream();
        this.requestComplete = false;
        this.expectedBodySize = -1;
    }

    public boolean read() throws IOException, PayloadTooLargeException {
        lastActiveTime = System.currentTimeMillis();
        readBuffer.clear();
        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            close();
            return false;
        }

        if (bytesRead > 0) {
            readBuffer.flip();
            byte[] data = new byte[readBuffer.remaining()];
            readBuffer.get(data);
            accumulatedData.write(data);

            // Check if we have enough data to parse
            if (accumulatedData.size() > MAX_BUFFER_SIZE) {
                throw new IOException("Request too large");
            }

            // Try to determine if we have a complete request
            ByteBuffer tempBuffer = ByteBuffer.wrap(accumulatedData.toByteArray());
            HttpRequest tempRequest = HttpParser.parseHeadersOnly(tempBuffer);
            if (tempRequest != null) {
                String contentLength = tempRequest.getHeaders().get("Content-Length");
                String transferEncoding = tempRequest.getHeaders().get("Transfer-Encoding");
                
                if (transferEncoding != null && transferEncoding.contains("chunked")) {
                    // For chunked, we parse when we have complete chunks
                    // Try full parse
                    tempBuffer.rewind();
                    try {
                        HttpRequest fullRequest = HttpParser.parse(tempBuffer);
                        if (fullRequest != null && fullRequest.getBody() != null) {
                            requestComplete = true;
                        }
                    } catch (PayloadTooLargeException e) {
                        throw e;
                    } catch (Exception e) {
                        // Not complete yet
                    }
                } else if (contentLength != null) {
                    try {
                        expectedBodySize = Integer.parseInt(contentLength);
                        if (expectedBodySize < 0) {
                            throw new IOException("Invalid Content-Length");
                        }
                        if (expectedBodySize > config.getMaxBodySize()) {
                            throw new PayloadTooLargeException();
                        }
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid Content-Length");
                    }

                    // Check if we have the complete body
                    int headerEnd = findHeaderEnd(accumulatedData.toByteArray());
                    if (headerEnd >= 0) {
                        int bodyReceived = accumulatedData.size() - headerEnd - 4; // -4 for \r\n\r\n
                        if (bodyReceived >= expectedBodySize) {
                            requestComplete = true;
                        }
                    }
                } else {
                    // No body expected, request is complete after headers
                    requestComplete = true;
                }
            }
        }

        return requestComplete;
    }

    private int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i+1] == '\n' && data[i+2] == '\r' && data[i+3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    public void prepareResponse() {
        ByteBuffer fullBuffer = ByteBuffer.wrap(accumulatedData.toByteArray());
        HttpRequest request = null;
        try {
            request = HttpParser.parse(fullBuffer);
        } catch (PayloadTooLargeException e) {
            writeBuffer = createErrorResponse(413, "Payload Too Large");
            return;
        } catch (Exception e) {
            // Protect against malformed requests crashing parser
        }

        if (request == null) {
            writeBuffer = createErrorResponse(400, "Bad Request");
            return;
        }

        // Periodically cleanup expired sessions (every 100 requests approximately)
        if (Math.random() < 0.01) {
            sessionManager.cleanupExpiredSessions();
        }

        Router router = new Router();
        Route defaultRoute = new Route("/", documentRoot);
        router.setDefaultRoute(defaultRoute);

        String uploadDir = documentRoot + "/uploads";
        Route uploadRoute = new Route("/upload", uploadDir);
        uploadRoute.addAcceptedMethod("POST");
        router.addRoute(uploadRoute);

        String cgiDir = documentRoot + "/cgi-bin";
        Route cgiRoute = new Route("/cgi-bin", cgiDir);
        router.addRoute(cgiRoute);

        // Reject paths with ".." to prevent path traversal attacks
        if (request.getPath().contains("..")) {
            writeBuffer = createErrorResponse(403, "Forbidden");
            return;
        }

        Route route = router.resolve(request.getPath());
        if (route != null) {
            // Check method
            if (!route.isMethodAccepted(request.getMethod())) {
                writeBuffer = createErrorResponse(405, "Method Not Allowed");
            } else if (route.isRedirect()) {
                writeBuffer = createRedirectResponse(route.getRedirectPath());
            } else if (request.getPath().startsWith("/upload") && "POST".equalsIgnoreCase(request.getMethod())) {
                UploadHandler uploadHandler = new UploadHandler(uploadDir, config);
                HttpResponse response = uploadHandler.handle(request);
                writeBuffer = response.toByteBuffer();
            } else if (request.getPath().startsWith("/cgi-bin")) {
                CGIHandler cgiHandler = new CGIHandler(cgiDir, config);
                HttpResponse response = cgiHandler.handle(request);
                writeBuffer = response.toByteBuffer();
            } else {
                StaticHandler handler = new StaticHandler(route.getDocumentRoot(), config);
                HttpResponse response = handler.handle(request);
                writeBuffer = response.toByteBuffer();
            }
        } else {
            writeBuffer = createErrorResponse(404, "Not Found");
        }
    }

    public void prepareErrorResponse(int statusCode, String statusText) {
        writeBuffer = createErrorResponse(statusCode, statusText);
    }

    private ByteBuffer createErrorResponse(int statusCode, String statusText) {
        String errorPagePath = config.getErrorPages().get(statusCode);
        if (errorPagePath != null) {
            try {
                java.nio.file.Path errorFile = java.nio.file.Paths.get(errorPagePath).toAbsolutePath().normalize();
                if (java.nio.file.Files.exists(errorFile) && java.nio.file.Files.isRegularFile(errorFile)) {
                    byte[] content = java.nio.file.Files.readAllBytes(errorFile);
                    String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: " + content.length + "\r\n" +
                            "Connection: close\r\n";
                    if (statusCode == 405) {
                        response += "Allow: GET, HEAD, DELETE, POST\r\n";
                    }
                    response += "\r\n";
                    byte[] headerBytes = response.getBytes();
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(headerBytes.length + content.length);
                    buffer.put(headerBytes);
                    buffer.put(content);
                    buffer.flip();
                    return buffer;
                }
            } catch (java.io.IOException e) {
                // Fall back to default error
            }
        }
        
        String body = statusCode + " " + statusText;
        String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n";
        if (statusCode == 405) {
            response += "Allow: GET, HEAD, DELETE, POST\r\n";
        }
        response += "\r\n" + body;
        return ByteBuffer.wrap(response.getBytes());
    }

    public boolean write() throws IOException {
        lastActiveTime = System.currentTimeMillis();
        if (writeBuffer == null) {
            return true;
        }
        
        channel.write(writeBuffer);
        return !writeBuffer.hasRemaining(); // Returns true when fully written
    }

    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    public static SessionManager getSessionManager() {
        return sessionManager;
    }

    private ByteBuffer createRedirectResponse(String location) {
        String body = "302 Found\nRedirecting to " + location;
        String response = "HTTP/1.1 302 Found\r\n" +
                "Location: " + location + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
        return ByteBuffer.wrap(response.getBytes());
    }
}
