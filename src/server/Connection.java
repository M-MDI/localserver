package server;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import config.ServerConfigurations;

public class Connection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private long lastActiveTime;
    private final ServerConfigurations configurations;
    private final int serverPort;
    private ServerConfig selectedConfig;
    private String documentRoot;
    private static final SessionManager sessionManager = new SessionManager();
    private ByteArrayOutputStream accumulatedData;
    private boolean requestComplete;
    private int expectedBodySize;
    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10MB max accumulation

    public Connection(SocketChannel channel, ServerConfigurations configurations, int serverPort) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = null;
        this.lastActiveTime = System.currentTimeMillis();
        this.configurations = configurations;
        this.serverPort = serverPort;
        this.accumulatedData = new ByteArrayOutputStream();
        this.requestComplete = false;
        this.expectedBodySize = -1;
    }

    // Backward compatibility constructor for single-server mode
    public Connection(SocketChannel channel, String documentRoot, ServerConfig config) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = null;
        this.lastActiveTime = System.currentTimeMillis();
        this.configurations = new ServerConfigurations();
        this.configurations.addServerConfig(config);
        int port = config.getPorts().isEmpty() ? 65432 : config.getPorts().get(0);
        this.configurations.setDefaultServerForPort(port, config);
        this.serverPort = port;
        this.documentRoot = documentRoot;
        this.selectedConfig = config;
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
                // Select server configuration based on Host header
                selectServerConfig(tempRequest);

                String contentLength = tempRequest.getHeader("Content-Length");
                String transferEncoding = tempRequest.getHeader("Transfer-Encoding");

                int maxAllowedBody = (selectedConfig != null) ? selectedConfig.getMaxBodySize() : MAX_BUFFER_SIZE;

                if (transferEncoding != null && transferEncoding.contains("chunked")) {
                    // For chunked, we parse when we have complete chunks
                    tempBuffer.rewind();
                    try {
                        HttpRequest fullRequest = HttpParser.parse(tempBuffer, maxAllowedBody);
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
                        if (expectedBodySize > maxAllowedBody) {
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

    private void selectServerConfig(HttpRequest request) {
        if (selectedConfig != null) {
            return;
        }

        String hostHeader = request.getHeader("Host");
        selectedConfig = configurations.selectServerConfig(serverPort, hostHeader);

        if (selectedConfig != null) {
            documentRoot = selectedConfig.getDocumentRoot();
        } else {
            selectedConfig = configurations.getDefaultServer(serverPort);
            if (selectedConfig != null) {
                documentRoot = selectedConfig.getDocumentRoot();
            }
        }
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
            int maxBody = (selectedConfig != null) ? selectedConfig.getMaxBodySize() : HttpParser.getMaxBodySize();
            request = HttpParser.parse(fullBuffer, maxBody);
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

        // Ensure server config is selected
        if (selectedConfig == null) {
            selectServerConfig(request);
        }

        // Periodically cleanup expired sessions (every 100 requests approximately)
        if (Math.random() < 0.01) {
            sessionManager.cleanupExpiredSessions();
        }

        Router router = new Router();
        if (selectedConfig != null && selectedConfig.hasRoutes()) {
            for (Route r : selectedConfig.getRoutes()) {
                if (r.getPath().equals("/")) {
                    router.setDefaultRoute(r);
                }
                router.addRoute(r);
            }
        } else {
            Route defaultRoute = new Route("/", documentRoot, "root");
            router.setDefaultRoute(defaultRoute);

            String uploadDir = documentRoot + "/uploads";
            Route uploadRoute = new Route("/upload", uploadDir, "upload");
            uploadRoute.addAcceptedMethod("POST");
            router.addRoute(uploadRoute);

            String cgiDir = documentRoot + "/cgi-bin";
            Route cgiRoute = new Route("/cgi-bin", cgiDir, "cgi");
            router.addRoute(cgiRoute);
        }

        if (selectedConfig != null && selectedConfig.hasRedirects()) {
            for (Route redir : selectedConfig.getRedirects()) {
                router.addRoute(redir);
            }
        }

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
                writeBuffer = createRedirectResponse(route.getRedirectCode(), route.getRedirectPath());
            } else if (route.isUpload()) {
                UploadHandler uploadHandler = new UploadHandler(route.getDocumentRoot(), selectedConfig, route.getPath());
                HttpResponse response = uploadHandler.handle(request);
                writeBuffer = response.toByteBuffer();
            } else if (route.isCgi()) {
                CGIHandler cgiHandler = new CGIHandler(route.getDocumentRoot(), selectedConfig, route.getPath());
                HttpResponse response = cgiHandler.handle(request);
                writeBuffer = response.toByteBuffer();
            } else {
                StaticHandler handler = new StaticHandler(route.getDocumentRoot(), selectedConfig, route.getPath());
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
        ServerConfig configToUse = selectedConfig;
        if (configToUse == null && configurations != null) {
            configToUse = configurations.getDefaultServer(serverPort);
        }

        String errorPagePath = null;
        if (configToUse != null && configToUse.getErrorPages() != null) {
            errorPagePath = configToUse.getErrorPages().get(statusCode);
        }

        if (errorPagePath != null) {
            try {
                Path errorFile = Paths.get(errorPagePath).toAbsolutePath().normalize();
                if (Files.exists(errorFile) && Files.isRegularFile(errorFile)) {
                    byte[] content = Files.readAllBytes(errorFile);
                    String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: " + content.length + "\r\n" +
                            "Connection: close\r\n";
                    if (statusCode == 405) {
                        response += "Allow: GET, HEAD, DELETE, POST\r\n";
                    }
                    response += "\r\n";
                    byte[] headerBytes = response.getBytes(StandardCharsets.UTF_8);
                    ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + content.length);
                    buffer.put(headerBytes);
                    buffer.put(content);
                    buffer.flip();
                    return buffer;
                }
            } catch (IOException e) {
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
        return ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
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
        return createRedirectResponse(302, location);
    }

    private ByteBuffer createRedirectResponse(int statusCode, String location) {
        if (location == null || location.contains("\r") || location.contains("\n")) {
            return createErrorResponse(500, "Internal Server Error");
        }

        String statusText;
        switch (statusCode) {
            case 301:
                statusText = "Moved Permanently";
                break;
            case 302:
                statusText = "Found";
                break;
            case 303:
                statusText = "See Other";
                break;
            case 307:
                statusText = "Temporary Redirect";
                break;
            case 308:
                statusText = "Permanent Redirect";
                break;
            default:
                statusText = "Found";
                statusCode = 302;
                break;
        }

        String body = statusCode + " " + statusText + "\nRedirecting to " + location;
        String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Location: " + location + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
        return ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
    }
}
