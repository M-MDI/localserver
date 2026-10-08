package server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import http.HttpParser;
import http.HttpRequest;
import http.HttpResponse;
import handler.StaticHandler;
import router.Route;
import router.Router;

public class Connection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private long lastActiveTime;
    private static final Router router;

    static {
        router = new Router();
        Route defaultRoute = new Route("/", "./www");
        router.setDefaultRoute(defaultRoute);
    }

    public Connection(SocketChannel channel) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = null;
        this.lastActiveTime = System.currentTimeMillis();
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
        } catch (Exception e) {
            // Protect against malformed requests crashing parser
        }

        if (request == null) {
            writeBuffer = createErrorResponse(400, "Bad Request");
            return;
        }

        Route route = router.resolve(request.getPath());
        if (route != null) {
            // Check method
            if (!route.isMethodAccepted(request.getMethod())) {
                writeBuffer = createErrorResponse(405, "Method Not Allowed");
            } else if (route.isRedirect()) {
                writeBuffer = createRedirectResponse(route.getRedirectPath());
            } else {
                StaticHandler handler = new StaticHandler(route.getDocumentRoot());
                HttpResponse response = handler.handle(request);
                writeBuffer = response.toByteBuffer();
            }
        } else {
            writeBuffer = createErrorResponse(404, "Not Found");
        }
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

    private ByteBuffer createErrorResponse(int statusCode, String statusText) {
        String body = statusCode + " " + statusText;
        String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
        return ByteBuffer.wrap(response.getBytes());
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
