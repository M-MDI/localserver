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
    }

    public boolean read() throws IOException {
        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            close();
            return false;
        }
        return bytesRead > 0;
    }

    public void prepareResponse() {
        readBuffer.flip();
        HttpRequest request = HttpParser.parse(readBuffer);
        if (request == null) {
            writeBuffer = createErrorResponse(400, "Bad Request");
            return;
        }

        Route route = router.resolve(request.getPath());
        if (route != null) {
            StaticHandler handler = new StaticHandler(route.getDocumentRoot());
            HttpResponse response = handler.handle(request);
            writeBuffer = response.toByteBuffer();
        } else {
            writeBuffer = createErrorResponse(404, "Not Found");
        }
    }

    public boolean write() throws IOException {
        if (writeBuffer == null) {
            return true;
        }
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer);
        }
        return true;
    }

    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
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
}
