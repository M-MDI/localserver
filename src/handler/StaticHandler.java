package handler;

import http.HttpRequest;
import http.HttpResponse;
import config.ServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StaticHandler {
    private final String documentRoot;
    private final ServerConfig config;

    public StaticHandler(String documentRoot, ServerConfig config) {
        this.documentRoot = documentRoot;
        this.config = config;
    }

    public HttpResponse handle(HttpRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return createErrorResponse(405, "Method Not Allowed");
        }

        String requestPath = request.getPath();
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }

        if (requestPath.contains("..")) {
            return createErrorResponse(403, "Forbidden");
        }

        Path resolvedPath = resolvePath(requestPath);
        if (resolvedPath == null) {
            return createErrorResponse(403, "Forbidden");
        }

        if ("DELETE".equalsIgnoreCase(method)) {
            return handleDelete(resolvedPath);
        } else if ("GET".equalsIgnoreCase(method)) {
            return handleGet(resolvedPath);
        } else {
            return createErrorResponse(405, "Method Not Allowed");
        }
    }

    private HttpResponse handleGet(Path resolvedPath) {
        if (!Files.exists(resolvedPath)) {
            return createErrorResponse(404, "Not Found");
        }

        if (!Files.isReadable(resolvedPath)) {
            return createErrorResponse(403, "Forbidden");
        }

        try {
            if (Files.isDirectory(resolvedPath)) {
                Path indexPath = resolvedPath.resolve("index.html");
                if (Files.exists(indexPath) && Files.isReadable(indexPath)) {
                    return serveFile(indexPath);
                } else {
                    return createErrorResponse(403, "Forbidden");
                }
            } else {
                return serveFile(resolvedPath);
            }
        } catch (IOException e) {
            return createErrorResponse(500, "Internal Server Error");
        }
    }

    private HttpResponse handleDelete(Path resolvedPath) {
        if (!Files.exists(resolvedPath)) {
            return createErrorResponse(404, "Not Found");
        }

        if (!Files.isWritable(resolvedPath)) {
            return createErrorResponse(403, "Forbidden");
        }

        if (Files.isDirectory(resolvedPath)) {
            return createErrorResponse(403, "Forbidden");
        }

        try {
            Files.delete(resolvedPath);
            HttpResponse response = new HttpResponse();
            response.setStatusCode(204);
            response.setStatusText("No Content");
            response.setHeader("Connection", "close");
            return response;
        } catch (IOException e) {
            return createErrorResponse(500, "Internal Server Error");
        }
    }
 
    private Path resolvePath(String requestPath) {
        try {
            if (requestPath.contains("..")) {
                return null;
            }

            String normalizedPath = requestPath.replace('\\', '/');
            if (normalizedPath.startsWith("/")) {
                normalizedPath = normalizedPath.substring(1);
            }

            if (normalizedPath.isEmpty()) {
                normalizedPath = ".";
            }

            Path rootPath = Paths.get(documentRoot).toAbsolutePath().normalize();
            Path requestedPath = rootPath.resolve(normalizedPath).normalize();

            if (!requestedPath.startsWith(rootPath)) {
                return null;
            }

            return requestedPath;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpResponse serveFile(Path filePath) throws IOException {
        byte[] content = Files.readAllBytes(filePath);
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusText("OK");
        response.setBody(content);
        response.setHeader("Content-Type", getMimeType(filePath.toString()));
        response.setHeader("Content-Length", String.valueOf(content.length));
        response.setHeader("Connection", "close");
        return response;
    }

    private String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html";
        } else if (lower.endsWith(".css")) {
            return "text/css";
        } else if (lower.endsWith(".js")) {
            return "application/javascript";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".txt")) {
            return "text/plain";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else {
            return "application/octet-stream";
        }
    }

    private HttpResponse createErrorResponse(int statusCode, String statusText) {
        String errorPagePath = config.getErrorPages().get(statusCode);
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
        return response;
    }
}
