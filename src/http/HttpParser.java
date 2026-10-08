package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

        // Parse Body
        if (headers.containsKey("Transfer-Encoding") && headers.get("Transfer-Encoding").contains("chunked")) {
            request.setBody(parseChunkedBody(buffer));
        } else if (headers.containsKey("Content-Length")) {
            try {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                if (contentLength > 0 && buffer.remaining() >= contentLength) {
                    byte[] bodyBytes = new byte[contentLength];
                    buffer.get(bodyBytes);
                    request.setBody(new String(bodyBytes, StandardCharsets.UTF_8));
                }
            } catch (NumberFormatException e) {
                // Invalid content length
            }
        }

        return request;
    }

    private static String parseChunkedBody(ByteBuffer buffer) {
        StringBuilder body = new StringBuilder();
        while (true) {
            String hexSize = readLine(buffer);
            if (hexSize == null || hexSize.isEmpty()) {
                break;
            }
            try {
                int chunkSize = Integer.parseInt(hexSize.trim(), 16);
                if (chunkSize == 0) {
                    readLine(buffer); // Consume trailing \r\n
                    break;
                }
                
                if (buffer.remaining() >= chunkSize) {
                    byte[] chunk = new byte[chunkSize];
                    buffer.get(chunk);
                    body.append(new String(chunk, StandardCharsets.UTF_8));
                    readLine(buffer); // Consume trailing \r\n after chunk data
                } else {
                    break; // Wait for more data
                }
            } catch (NumberFormatException e) {
                break; // Invalid chunk size
            }
        }
        return body.toString();
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
