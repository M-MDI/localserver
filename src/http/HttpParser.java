package http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpParser {
    private static int MAX_BODY_SIZE = 10 * 1024 * 1024; // 10 MB

    public static void setMaxBodySize(int size) {
        MAX_BODY_SIZE = size;
    }

    public static int getMaxBodySize() {
        return MAX_BODY_SIZE;
    }

    public static HttpRequest parse(ByteBuffer buffer) throws PayloadTooLargeException {
        return parse(buffer, MAX_BODY_SIZE);
    }

    public static HttpRequest parse(ByteBuffer buffer, int maxBodySize) throws PayloadTooLargeException {
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
        request.parseCookies();

        // Parse Body
        if (headers.containsKey("Transfer-Encoding") && headers.get("Transfer-Encoding").contains("chunked")) {
            request.setBody(parseChunkedBody(buffer, maxBodySize));
        } else if (headers.containsKey("Content-Length")) {
            try {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                if (contentLength < 0) {
                    return null; // Invalid Content-Length - malformed request
                }
                if (contentLength > maxBodySize) {
                    throw new PayloadTooLargeException();
                }
                request.setContentLength(contentLength);
                if (contentLength > 0 && buffer.remaining() >= contentLength) {
                    byte[] bodyBytes = new byte[contentLength];
                    buffer.get(bodyBytes);
                    request.setBody(new String(bodyBytes, StandardCharsets.UTF_8));
                } else if (contentLength > 0 && buffer.remaining() < contentLength) {
                    // Incomplete body - return null to indicate need more data
                    return null;
                }
                // If contentLength is 0, body remains null (will be caught by UploadHandler)
            } catch (NumberFormatException e) {
                // Invalid content length - treat as malformed
                return null;
            }
        }

        return request;
    }

    public static HttpRequest parseHeadersOnly(ByteBuffer buffer) {
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

    private static String parseChunkedBody(ByteBuffer buffer, int maxBodySize) throws PayloadTooLargeException {
        StringBuilder body = new StringBuilder();
        int totalSize = 0;
        while (true) {
            String hexSize = readLine(buffer);
            if (hexSize == null) {
                return null; // Incomplete chunked data
            }
            if (hexSize.isEmpty()) {
                break; // End of chunks
            }
            try {
                int chunkSize = Integer.parseInt(hexSize.trim(), 16);
                if (chunkSize < 0) {
                    return null; // Invalid chunk size
                }
                if (chunkSize == 0) {
                    readLine(buffer); // Consume trailing \r\n
                    break; // Last chunk
                }

                if (totalSize + chunkSize > maxBodySize) {
                    throw new PayloadTooLargeException();
                }

                if (buffer.remaining() >= chunkSize + 2) { // +2 for trailing \r\n
                    byte[] chunk = new byte[chunkSize];
                    buffer.get(chunk);
                    body.append(new String(chunk, StandardCharsets.UTF_8));
                    totalSize += chunkSize;
                    readLine(buffer); // Consume trailing \r\n after chunk data
                } else {
                    return null; // Incomplete chunk
                }
            } catch (NumberFormatException e) {
                return null; // Invalid chunk size - malformed
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
