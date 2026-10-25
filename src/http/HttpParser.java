package http;

import java.io.IOException;
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

    public static HttpRequest parse(ByteBuffer buffer) throws PayloadTooLargeException, IOException {
        return parse(buffer, MAX_BODY_SIZE);
    }

    public static HttpRequest parse(ByteBuffer buffer, int maxBodySize) throws PayloadTooLargeException, IOException {
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
        boolean headersEnded = false;
        while ((line = readLine(buffer)) != null) {
            if (line.isEmpty()) {
                headersEnded = true;
                break;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }
        if (!headersEnded) {
            return null; // Incomplete headers
        }

        request.setHeaders(headers);
        request.parseCookies();

        // Reject conflicting framing headers (RFC 9112 Section 6.1)
        if (headers.containsKey("Transfer-Encoding") && headers.containsKey("Content-Length")) {
            throw new IOException("Conflicting framing headers: Transfer-Encoding and Content-Length");
        }

        // Parse Body
        if (headers.containsKey("Transfer-Encoding") && headers.get("Transfer-Encoding").contains("chunked")) {
            request.setBody(parseChunkedBody(buffer, maxBodySize));
        } else if (headers.containsKey("Content-Length")) {
            try {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                if (contentLength < 0) {
                    throw new IOException("Invalid Content-Length: negative");
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
                throw new IOException("Invalid Content-Length");
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
        boolean headersEnded = false;
        while ((line = readLine(buffer)) != null) {
            if (line.isEmpty()) {
                headersEnded = true;
                break;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }
        if (!headersEnded) {
            return null;
        }

        request.setHeaders(headers);
        return request;
    }

    private static String parseChunkedBody(ByteBuffer buffer, int maxBodySize) throws PayloadTooLargeException, IOException {
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
                    throw new IOException("Invalid chunk size: negative");
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
                throw new IOException("Invalid chunk size: " + hexSize);
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
