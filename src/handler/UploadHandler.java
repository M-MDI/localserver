package handler;

import http.HttpRequest;
import http.HttpResponse;
import config.ServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UploadHandler {
    private final String uploadDirectory;
    private final ServerConfig config;
    private final String routePrefix;

    public UploadHandler(String uploadDirectory, ServerConfig config) {
        this(uploadDirectory, config, "/upload");
    }

    public UploadHandler(String uploadDirectory, ServerConfig config, String routePrefix) {
        this.uploadDirectory = uploadDirectory;
        this.config = config;
        this.routePrefix = (routePrefix != null && !routePrefix.isEmpty()) ? routePrefix : "/upload";
    }

    public HttpResponse handle(HttpRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return createErrorResponse(405, "Method Not Allowed");
        }

        String requestPath = request.getPath();
        String prefixWithSlash = routePrefix.endsWith("/") ? routePrefix : routePrefix + "/";
        if (requestPath == null || !requestPath.startsWith(prefixWithSlash)) {
            return createErrorResponse(400, "Bad Request");
        }

        String filename = requestPath.substring(prefixWithSlash.length());
        // Strip query string if present
        int queryIndex = filename.indexOf('?');
        if (queryIndex >= 0) {
            filename = filename.substring(0, queryIndex);
        }
        if (filename.isEmpty()) {
            return createErrorResponse(400, "Bad Request - Missing filename");
        }

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\") || filename.contains(":")) {
            return createErrorResponse(403, "Forbidden - Invalid filename");
        }

        String body = request.getBody();
        int contentLength = request.getContentLength();
        
        // Reject empty body or no body
        if (body == null || body.isEmpty() || body.getBytes().length == 0) {
            return createErrorResponse(400, "Bad Request - Empty body");
        }
        
        // Also reject if contentLength is explicitly 0
        if (contentLength == 0) {
            return createErrorResponse(400, "Bad Request - Empty body");
        }

        // Defense-in-depth: validate body size against declared Content-Length
        if (contentLength >= 0 && body.getBytes().length != contentLength) {
            return createErrorResponse(400, "Bad Request - Body size mismatch");
        }

        // Check if upload would exceed max_body_size
        if (body.getBytes().length > config.getMaxBodySize()) {
            return createErrorResponse(413, "Payload Too Large");
        }

        try {
            Path uploadDir = Paths.get(uploadDirectory).toAbsolutePath().normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            Path targetFile = uploadDir.resolve(filename).normalize();
            if (!targetFile.startsWith(uploadDir)) {
                return createErrorResponse(403, "Forbidden - Invalid path");
            }

            // Do not overwrite existing files
            if (Files.exists(targetFile)) {
                return createErrorResponse(409, "Conflict - File already exists");
            }

            // Write to temporary file first, then atomically move to avoid partial writes
            Path tempFile = uploadDir.resolve("." + filename + ".tmp." + System.currentTimeMillis());
            try {
                Files.write(tempFile, body.getBytes());
                Files.move(tempFile, targetFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Clean up temp file on failure
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
                throw e;
            }

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
        String errorPagePath = (config != null && config.getErrorPages() != null) ? config.getErrorPages().get(statusCode) : null;
        if (errorPagePath != null) {
            try {
                Path errorFile = Paths.get(errorPagePath).toAbsolutePath().normalize();
                if (Files.exists(errorFile) && Files.isRegularFile(errorFile)) {
                    byte[] content = Files.readAllBytes(errorFile);
                    HttpResponse response = new HttpResponse();
                    response.setStatusCode(statusCode);
                    response.setStatusText(statusText);
                    response.setBody(content);
                    response.setHeader("Content-Type", "text/html; charset=UTF-8");
                    response.setHeader("Content-Length", String.valueOf(content.length));
                    response.setHeader("Connection", "close");
                    if (statusCode == 405) {
                        response.setHeader("Allow", "POST");
                    }
                    return response;
                }
            } catch (IOException e) {
                // Fall back to default error
            }
        }
        
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setStatusText(statusText);
        String body = statusCode + " " + statusText;
        response.setBody(body.getBytes());
        response.setHeader("Content-Type", "text/plain");
        response.setHeader("Content-Length", String.valueOf(body.length()));
        response.setHeader("Connection", "close");
        if (statusCode == 405) {
            response.setHeader("Allow", "POST");
        }
        return response;
    }
}
