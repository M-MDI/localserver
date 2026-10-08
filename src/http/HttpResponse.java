package http;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private int statusCode;
    private String statusText;
    private Map<String, String> headers;
    private byte[] body;

    public HttpResponse() {
        this.headers = new HashMap<>();
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    public ByteBuffer toByteBuffer() {
        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }

        headerBuilder.append("\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + (body != null ? body.length : 0));
        buffer.put(headerBytes);
        if (body != null) {
            buffer.put(body);
        }
        buffer.flip();
        return buffer;
    }
}
