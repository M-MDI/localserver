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
    private static final long CGI_TIMEOUT_MS = 5000; // 5 second timeout

    public CGIHandler(String cgiDirectory, ServerConfig config) {
        this.cgiDirectory = cgiDirectory;
        this.config = config;
    }

    public HttpResponse handle(HttpRequest request) {
        String requestPath = request.getPath();
        if (requestPath == null || !requestPath.startsWith("/cgi-bin/")) {
            return createErrorResponse(404, "Not Found");
        }

        // Reject path traversal at the CGI handler level
        if (requestPath.contains("..")) {
            return createErrorResponse(403, "Forbidden - Path traversal detected");
        }

        String pathAfterCgiBin = requestPath.substring("/cgi-bin/".length());
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

            // Skip executable check on Windows as Files.isExecutable() doesn't work reliably for scripts
            // The interpreter will handle execution; if the script is malformed, it will fail at runtime
            // String osName = System.getProperty("os.name").toLowerCase();
            // if (!osName.contains("win") && !Files.isExecutable(scriptPath)) {
            //     return createErrorResponse(403, "Forbidden - Script not executable");
            // }

            String extension = getFileExtension(scriptName);
            String interpreter = getInterpreter(extension);
            if (interpreter == null) {
                return createErrorResponse(403, "Forbidden - Unsupported script type");
            }

            // Note: We don't verify interpreter existence at startup to allow for
            // different installations (e.g., python vs python3 vs py on Windows).
            // If the interpreter is missing, the process will fail at runtime.

            Map<String, String> env = buildEnvironment(request, scriptName, pathInfo);
            String body = request.getBody();

            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = new ArrayList<>();
            command.add(interpreter);
            command.add(scriptPath.toString());
            pb.command(command);
            pb.directory(cgiDir.toFile());

            Map<String, String> processEnv = pb.environment();
            processEnv.putAll(env);

            // Redirect stderr to prevent deadlock (architectural limitation: cannot consume stderr in single-threaded event loop)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();

            if (body != null && !body.isEmpty()) {
                try (OutputStream processInput = process.getOutputStream()) {
                    processInput.write(body.getBytes());
                    processInput.flush();
                }
            }

            boolean finished = process.waitFor(CGI_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return createErrorResponse(504, "Gateway Timeout - CGI script timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return createErrorResponse(500, "Internal Server Error - CGI script failed with exit code " + exitCode);
            }

            InputStream processOutput = process.getInputStream();
            String output = readAllText(processOutput);

            return parseCGIOutput(output);

        } catch (IOException e) {
            return createErrorResponse(500, "Internal Server Error - " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createErrorResponse(500, "Internal Server Error - CGI execution interrupted");
        }
    }

    private Map<String, String> buildEnvironment(HttpRequest request, String scriptName, String pathInfo) {
        Map<String, String> env = new HashMap<>();
        env.put("REQUEST_METHOD", request.getMethod() != null ? request.getMethod() : "GET");
        env.put("SCRIPT_NAME", "/cgi-bin/" + scriptName);
        env.put("PATH_INFO", pathInfo);

        String queryString = extractQueryString(request.getPath());
        env.put("QUERY_STRING", queryString != null ? queryString : "");

        String body = request.getBody();
        if (body != null && !body.isEmpty()) {
            env.put("CONTENT_LENGTH", String.valueOf(body.length()));
            String contentType = request.getHeaders().get("Content-Type");
            if (contentType != null) {
                env.put("CONTENT_TYPE", contentType);
            }
        }

        return env;
    }

    private String extractQueryString(String pathWithQuery) {
        if (pathWithQuery == null) {
            return null;
        }
        int queryIndex = pathWithQuery.indexOf('?');
        if (queryIndex >= 0 && queryIndex < pathWithQuery.length() - 1) {
            return pathWithQuery.substring(queryIndex + 1);
        }
        return null;
    }

    private HttpResponse parseCGIOutput(String output) {
        if (output == null || output.isEmpty()) {
            return createErrorResponse(500, "Internal Server Error - Empty CGI output");
        }

        String[] lines = output.split("\r?\n");
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusText("OK");

        StringBuilder bodyBuilder = new StringBuilder();
        boolean headersDone = false;

        for (String line : lines) {
            if (!headersDone) {
                if (line.isEmpty()) {
                    headersDone = true;
                } else {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String name = line.substring(0, colonIndex).trim();
                        String value = line.substring(colonIndex + 1).trim();
                        if (name.equalsIgnoreCase("Status")) {
                            String[] statusParts = value.split(" ", 2);
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