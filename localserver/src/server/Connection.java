import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Connection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private static final String RESPONSE =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 23\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "LocalServer is running.";

    public Connection(SocketChannel channel) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = ByteBuffer.wrap(RESPONSE.getBytes());
    }

    public boolean read() throws IOException {
        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            close();
            return false;
        }
        return true;
    }

    public void prepareResponse() {
        writeBuffer.rewind();
    }

    public boolean write() throws IOException {
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
}
