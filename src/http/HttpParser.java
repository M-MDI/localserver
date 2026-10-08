package http;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HttpParser {
    public static HttpRequest parse(ByteBuffer buffer) {
        HttpRequest request = new HttpRequest();
        String requestLine = readLine(buffer);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }

        String[] parts = requestLine.split(" ");
        if (parts.length >= 2) {
            request.setMethod(parts[0]);
            request.setPath(parts[1]);
            if (parts.length >= 3) {
                request.setVersion(parts[2]);
            }
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(buffer)) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }
        request.setHeaders(headers);

        return request;
    }

    private static String readLine(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        buffer.mark();
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == '\r') {
                if (buffer.hasRemaining() && buffer.get() == '\n') {
                    return sb.toString();
                }
            } else if (b == '\n') {
                return sb.toString();
            } else {
                sb.append((char) b);
            }
        }
        buffer.reset();
        return null;
    }
}
