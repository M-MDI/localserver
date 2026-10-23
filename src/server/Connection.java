package server;

import java.io.IOException;
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

    public Connection(SocketChannel channel, String documentRoot, ServerConfig config) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = null;
        this.lastActiveTime = System.currentTimeMillis();
        this.documentRoot = documentRoot;
        this.config = config;
    }

    public boolean read() throws IOException {
        lastActiveTime = System.currentTimeMillis();
        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            close();
            return false;
        }
        return bytesRead > 0;
    }

    public void prepareResponse() {
        readBuffer.flip();
        HttpRequest request = null;
        try {
            request = HttpParser.parse(readBuffer);
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
                            "Connection: close\r\n" +
                            "\r\n";
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
                "Connection: close\r\n" +
                "\r\n" +
                body;
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
