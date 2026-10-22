package handler;

import http.HttpRequest;
import http.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UploadHandler {
    private final String uploadDirectory;

    public UploadHandler(String uploadDirectory) {
        this.uploadDirectory = uploadDirectory;
    }

    public HttpResponse handle(HttpRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return createErrorResponse(405, "Method Not Allowed");
        }

        String requestPath = request.getPath();
        if (requestPath == null || !requestPath.startsWith("/upload/")) {
            return createErrorResponse(400, "Bad Request");
        }

        String filename = requestPath.substring("/upload/".length());
        if (filename.isEmpty()) {
            return createErrorResponse(400, "Bad Request - Missing filename");
        }

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\") || filename.contains(":")) {
            return createErrorResponse(403, "Forbidden - Invalid filename");
        }

        String body = request.getBody();
        if (body == null || body.isEmpty()) {
            return createErrorResponse(400, "Bad Request - Empty body");
        }

        try {
            Path uploadDir = Paths.get(uploadDirectory).toAbsolutePath().normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            Path targetFile = uploadDir.resolve(filename).normalize();
            if (!targetFile.startsWith(uploadDir)) {
                return createErrorResponse(403, "Forbidden - Path traversal detected");
            }

            if (Files.exists(targetFile)) {
                return createErrorResponse(409, "Conflict - File already exists");
            }

            Files.write(targetFile, body.getBytes());

            HttpResponse response = new HttpResponse();
            response.setStatusCode(201);
            response.setStatusText("Created");
            response.setBody(("File uploaded: " + filename).getBytes());
            response.setHeader("Content-Type", "text/plain");
            response.setHeader("Content-Length", String.valueOf(("File uploaded: " + filename).length()));
            response.setHeader("Connection", "close");
            return response;
        } catch (IOException e) {
            return createErrorResponse(500, "Internal Server Error");
        }
    }

    private HttpResponse createErrorResponse(int statusCode, String statusText) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setStatusText(statusText);
        String body = statusCode + " " + statusText;
        response.setBody(body.getBytes());
        response.setHeader("Content-Type", "text/plain");
        response.setHeader("Content-Length", String.valueOf(body.length()));
        response.setHeader("Connection", "close");
        return response;
    }
}
