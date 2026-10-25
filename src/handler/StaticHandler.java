package handler;

import http.HttpRequest;
import http.HttpResponse;
import config.ServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

public class StaticHandler {
    private final String documentRoot;
    private final ServerConfig config;
    private final String routePrefix;

    public StaticHandler(String documentRoot, ServerConfig config) {
        this(documentRoot, config, null);
    }

    public StaticHandler(String documentRoot, ServerConfig config, String routePrefix) {
        this.documentRoot = documentRoot;
        this.config = config;
        this.routePrefix = routePrefix;
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
                String indexName = (config != null && config.getIndexFile() != null) ? config.getIndexFile() : "index.html";
                Path indexPath = resolvedPath.resolve(indexName);
                if (Files.exists(indexPath) && Files.isReadable(indexPath)) {
                    return serveFile(indexPath);
                } else {
                    if (config != null && config.isDirectoryListing()) {
                        return generateDirectoryListing(resolvedPath);
                    } else {
                        return createErrorResponse(403, "Forbidden");
                    }
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
            String normalizedPath = requestPath.replace('\\', '/');
            if (normalizedPath.startsWith("/")) {
                normalizedPath = normalizedPath.substring(1);
            }

            // Strip query string if present
            int queryIndex = normalizedPath.indexOf('?');
            if (queryIndex >= 0) {
                normalizedPath = normalizedPath.substring(0, queryIndex);
            }

            if (normalizedPath.isEmpty()) {
                normalizedPath = ".";
            }

            Path rootPath = Paths.get(documentRoot).toAbsolutePath().normalize();

            // If a route prefix is provided (e.g. "/api"), attempt to resolve relative to route prefix first
            if (routePrefix != null && !routePrefix.equals("/") && requestPath.startsWith(routePrefix)) {
                String sub = requestPath.substring(routePrefix.length());
                if (sub.startsWith("/")) {
                    sub = sub.substring(1);
                }
                int qIdx = sub.indexOf('?');
                if (qIdx >= 0) {
                    sub = sub.substring(0, qIdx);
                }
                if (sub.isEmpty()) {
                    sub = ".";
                }
                Path candidate = rootPath.resolve(sub).normalize();
                if (candidate.startsWith(rootPath) && (Files.exists(candidate) || !Files.exists(rootPath.resolve(normalizedPath).normalize()))) {
                    return candidate;
                }
            }

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

    private HttpResponse generateDirectoryListing(Path dirPath) throws IOException {
        StringBuilder html = new StringBuilder();
        String relativePath = getRelativePath(dirPath);
        
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("<title>Index of ");
        html.append(escapeHtml(relativePath.isEmpty() ? "/" : relativePath));
        html.append("</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 40px; }\n");
        html.append("h1 { color: #333; }\n");
        html.append("table { border-collapse: collapse; width: 100%; }\n");
        html.append("th, td { text-align: left; padding: 8px; border-bottom: 1px solid #ddd; }\n");
        html.append("th { background-color: #f2f2f2; }\n");
        html.append("a { color: #0066cc; text-decoration: none; }\n");
        html.append("a:hover { text-decoration: underline; }\n");
        html.append("</style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<h1>Index of ");
        html.append(escapeHtml(relativePath.isEmpty() ? "/" : relativePath));
        html.append("</h1>\n");
        html.append("<table>\n");
        html.append("<tr><th>Name</th><th>Type</th></tr>\n");
        
        // Add parent directory link if not at document root
        if (!relativePath.isEmpty()) {
            String parentPath = getParentPath(relativePath);
            html.append("<tr><td><a href=\"");
            html.append(escapeHtml(parentPath));
            html.append("\">../</a></td><td>Directory</td></tr>\n");
        }
        
        try (Stream<Path> stream = Files.list(dirPath)) {
            stream.sorted()
                  .forEach(path -> {
                      String name = path.getFileName().toString();
                      String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
                      boolean isDirectory = Files.isDirectory(path);
                      
                      html.append("<tr><td><a href=\"");
                      html.append(escapeHtml(encodedName));
                      if (isDirectory) {
                          html.append("/");
                      }
                      html.append("\">");
                      html.append(escapeHtml(name));
                      if (isDirectory) {
                          html.append("/");
                      }
                      html.append("</a></td><td>");
                      html.append(isDirectory ? "Directory" : "File");
                      html.append("</td></tr>\n");
                  });
        }
        
        html.append("</table>\n");
        html.append("</body>\n");
        html.append("</html>\n");
        
        byte[] content = html.toString().getBytes(StandardCharsets.UTF_8);
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusText("OK");
        response.setBody(content);
        response.setHeader("Content-Type", "text/html; charset=UTF-8");
        response.setHeader("Content-Length", String.valueOf(content.length));
        response.setHeader("Connection", "close");
        return response;
    }

    private String getRelativePath(Path fullPath) {
        Path rootPath = Paths.get(documentRoot).toAbsolutePath().normalize();
        Path relative = rootPath.relativize(fullPath).normalize();
        String result = relative.toString().replace("\\", "/");
        if (result.equals(".")) {
            return "";
        }
        return result;
    }

    private String getParentPath(String relativePath) {
        if (relativePath.isEmpty() || relativePath.equals("/")) {
            return "/";
        }
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash == -1) {
            return "/";
        }
        if (lastSlash == 0) {
            return "/";
        }
        return relativePath.substring(0, lastSlash);
    }

    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
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
                        response.setHeader("Allow", "GET, DELETE");
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
            response.setHeader("Allow", "GET, DELETE");
        }
        return response;
    }
}
