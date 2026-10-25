package handler;

import http.HttpRequest;
import http.HttpResponse;
import config.ServerConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CGIHandler {
    private final String cgiDirectory;
    private final ServerConfig config;
    private final String routePrefix;
    private static final long CGI_TIMEOUT_MS = 5000; // 5 second timeout

    public CGIHandler(String cgiDirectory, ServerConfig config) {
        this(cgiDirectory, config, "/cgi-bin");
    }

    public CGIHandler(String cgiDirectory, ServerConfig config, String routePrefix) {
        this.cgiDirectory = cgiDirectory;
        this.config = config;
        this.routePrefix = (routePrefix != null && !routePrefix.isEmpty()) ? routePrefix : "/cgi-bin";
    }

    public HttpResponse handle(HttpRequest request) {
        String requestPath = request.getPath();
        String prefixWithSlash = routePrefix.endsWith("/") ? routePrefix : routePrefix + "/";
        if (requestPath == null || (!requestPath.equals(routePrefix) && !requestPath.startsWith(prefixWithSlash))) {
            return createErrorResponse(404, "Not Found");
        }

        // Reject path traversal at the CGI handler level
        if (requestPath.contains("..")) {
            return createErrorResponse(403, "Forbidden - Path traversal detected");
        }

        String pathAfterCgiBin = requestPath.startsWith(prefixWithSlash) ? requestPath.substring(prefixWithSlash.length()) : "";
        if (pathAfterCgiBin.isEmpty()) {
            return createErrorResponse(404, "Not Found");
        }

        String queryString = extractQueryString(request.getPath());

        String[] parts = pathAfterCgiBin.split("/", 2);
        String scriptName = parts[0];
        String pathInfo = parts.length > 1 ? "/" + parts[1] : "";
        
        if (pathInfo.contains("?")) {
            pathInfo = pathInfo.substring(0, pathInfo.indexOf('?'));
        }

        if (scriptName.contains("..") || scriptName.contains("/") || scriptName.contains("\\") || scriptName.contains(":")) {
            return createErrorResponse(403, "Forbidden - Invalid script name");
        }

        try {
            Path cgiDir = Paths.get(cgiDirectory).toAbsolutePath().normalize();
            if (!Files.exists(cgiDir) || !Files.isDirectory(cgiDir)) {
                return createErrorResponse(500, "Internal Server Error - CGI directory not found");
            }

            Path scriptPath = cgiDir.resolve(scriptName).normalize();
            if (!scriptPath.startsWith(cgiDir)) {
                return createErrorResponse(403, "Forbidden - Path traversal detected");
            }

            if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
                return createErrorResponse(404, "Not Found - Script not found");
            }

            String extension = getFileExtension(scriptName);
            String interpreter = getInterpreter(extension);
            if (interpreter == null) {
                return createErrorResponse(403, "Forbidden - Unsupported script type");
            }

            Map<String, String> env = buildEnvironment(request, scriptName, pathInfo);
            String body = request.getBody();

            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = new ArrayList<>();
            command.add(interpreter);
            command.add(scriptPath.toString());
            pb.command(command);
            pb.directory(cgiDir.toFile());

            // Clear environment and add only CGI variables
            pb.environment().clear();
            pb.environment().putAll(env);

            // Redirect stderr to prevent deadlock
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();

            // Send body to script stdin if present
            if (body != null && !body.isEmpty()) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }

            // Read script output with timeout
            boolean finished = process.waitFor(CGI_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return createErrorResponse(500, "Internal Server Error - CGI script timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return createErrorResponse(500, "Internal Server Error - Script exited with code " + exitCode);
            }

            // Parse CGI output
            String output = readAllText(process.getInputStream());
            return parseCGIOutput(output);

        } catch (IOException e) {
            return createErrorResponse(500, "Internal Server Error - Failed to execute script: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createErrorResponse(500, "Internal Server Error - CGI execution interrupted");
        }
    }

    private String extractQueryString(String path) {
        int questionIndex = path.indexOf('?');
        if (questionIndex >= 0 && questionIndex < path.length() - 1) {
            return path.substring(questionIndex + 1);
        }
        return "";
    }

    private Map<String, String> buildEnvironment(HttpRequest request, String scriptName, String pathInfo) {
        Map<String, String> env = new HashMap<>();

        // Required CGI/1.1 environment variables
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("SCRIPT_NAME", routePrefix.endsWith("/") ? routePrefix + scriptName : routePrefix + "/" + scriptName);
        env.put("PATH_INFO", pathInfo);
        env.put("QUERY_STRING", extractQueryString(request.getPath()));
        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("SERVER_SOFTWARE", "LocalServer/1.0");
        env.put("GATEWAY_INTERFACE", "CGI/1.1");

        // Set Content-Length if present
        String contentLength = request.getHeaders().get("Content-Length");
        if (contentLength != null) {
            env.put("CONTENT_LENGTH", contentLength);
        }

        // Set Content-Type if present
        String contentType = request.getHeaders().get("Content-Type");
        if (contentType != null) {
            env.put("CONTENT_TYPE", contentType);
        }

        // Pass HTTP headers as HTTP_* environment variables
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            String name = entry.getKey().toUpperCase().replace('-', '_');
            if (!name.equals("CONTENT_LENGTH") && !name.equals("CONTENT_TYPE")) {
                env.put("HTTP_" + name, entry.getValue());
            }
        }

        return env;
    }

    private HttpResponse parseCGIOutput(String output) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusText("OK");

        if (output == null || output.isEmpty()) {
            response.setBody("".getBytes());
            response.setHeader("Content-Length", "0");
            response.setHeader("Connection", "close");
            return response;
        }

        String[] lines = output.split("\r?\n");
        boolean parsingHeaders = true;
        StringBuilder bodyBuilder = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (parsingHeaders) {
                if (line.isEmpty()) {
                    parsingHeaders = false;
                    continue;
                }

                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String name = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();

                    if (!name.isEmpty()) {
                        if (name.equalsIgnoreCase("Status")) {
                            String[] statusParts = value.split("\\s+", 2);
                            if (statusParts.length >= 1) {
                                try {
                                    int statusCode = Integer.parseInt(statusParts[0]);
                                    response.setStatusCode(statusCode);
                                    if (statusParts.length >= 2) {
                                        response.setStatusText(statusParts[1]);
                                    } else {
                                        response.setStatusText(getDefaultStatusText(statusCode));
                                    }
                                } catch (NumberFormatException e) {
                                    // Keep default 200 OK
                                }
                            }
                        } else if (!name.equalsIgnoreCase("HTTP") && !name.equalsIgnoreCase("Server")) {
                            response.setHeader(name, value);
                        }
                    }
                } else {
                    parsingHeaders = false;
                    bodyBuilder.append(line).append("\n");
                }
            } else {
                bodyBuilder.append(line).append("\n");
            }
        }

        String body = bodyBuilder.toString();
        if (!body.isEmpty()) {
            response.setBody(body.getBytes());
            if (!response.getHeaders().containsKey("Content-Length")) {
                response.setHeader("Content-Length", String.valueOf(body.length()));
            }
        }

        response.setHeader("Connection", "close");
        return response;
    }

    private String getDefaultStatusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "Unknown";
        }
    }

    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1).toLowerCase();
        }
        return null;
    }

    private String getInterpreter(String extension) {
        switch (extension) {
            case "py":
                return "python";
            case "sh":
                return "sh";
            case "pl":
                return "perl";
            default:
                return null;
        }
    }

    private String readAllText(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
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
