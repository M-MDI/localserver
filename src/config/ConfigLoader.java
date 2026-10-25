package config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import router.Route;

public class ConfigLoader {
    private static final String DEFAULT_CONFIG_PATH = "config/server.conf";
    private static final int DEFAULT_PORT = 65432;
    private static final String DEFAULT_DOCUMENT_ROOT = "./www";
    private static final int DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024; // 10 MB

    public static ServerConfig load() throws IOException {
        return load(DEFAULT_CONFIG_PATH);
    }

    public static ServerConfig load(String configPath) throws IOException {
        ServerConfigurations configs = loadMultiServer(configPath);
        List<ServerConfig> allConfigs = configs.getAllConfigs();

        if (allConfigs.isEmpty()) {
            throw new IOException("No server configurations found");
        }

        // For backward compatibility, if only one config exists, return it directly
        if (allConfigs.size() == 1) {
            return allConfigs.get(0);
        }

        throw new IOException("Multiple server configurations found. Use loadMultiServer() instead.");
    }

    public static ServerConfigurations loadMultiServer() throws IOException {
        return loadMultiServer(DEFAULT_CONFIG_PATH);
    }

    public static ServerConfigurations loadMultiServer(String configPath) throws IOException {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + configPath);
        }

        List<String> rawLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            // Strip UTF-8 BOM if present
            lines.add(line.replace("\uFEFF", ""));
        }

        ServerConfigurations configurations = new ServerConfigurations();

        // Check if file uses server blocks (multi-server mode)
        boolean hasServerBlocks = lines.stream().anyMatch(l -> {
            String trimmed = l.trim();
            return trimmed.startsWith("server") && 
                   (trimmed.equals("server") || trimmed.startsWith("server ") || trimmed.startsWith("server{") || trimmed.startsWith("server\t"));
        });

        if (hasServerBlocks) {
            return parseServerBlocks(lines, configPath);
        } else {
            // Backward compatibility: single-server mode
            ServerConfig config = parseSingleServer(lines, configPath);
            configurations.addServerConfig(config);
            for (int port : config.getPorts()) {
                configurations.setDefaultServerForPort(port, config);
            }
            return configurations;
        }
    }

    private static ServerConfig parseSingleServer(List<String> lines, String configPath) throws IOException {
        ServerConfig config = new ServerConfig();
        List<Integer> ports = new ArrayList<>();
        String documentRoot = null;
        List<String> rawRoutes = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Strip inline comment
            if (line.contains("#")) {
                line = line.substring(0, line.indexOf('#')).trim();
                if (line.isEmpty()) {
                    continue;
                }
            }

            if (line.startsWith("route ") || line.startsWith("route=")) {
                String routeVal = line.startsWith("route=") ? line.substring(6).trim() : line.substring(6).trim();
                rawRoutes.add(routeVal);
                continue;
            }

            String[] parts = line.split("=", 2);
            if (parts.length != 2) {
                continue;
            }

            String key = parts[0].trim();
            String value = parts[1].trim();

            if (key.equalsIgnoreCase("port")) {
                try {
                    int port = Integer.parseInt(value);
                    if (port < 1 || port > 65535) {
                        throw new IOException("Invalid port number: " + port + " (must be 1-65535)");
                    }
                    ports.add(port);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid port number: " + value);
                }
            } else if (key.equalsIgnoreCase("document_root")) {
                documentRoot = value;
            } else if (key.equalsIgnoreCase("max_body_size")) {
                try {
                    int size = parseSize(value);
                    if (size <= 0) {
                        throw new IOException("Invalid max_body_size: " + value + " (must be positive)");
                    }
                    config.setMaxBodySize(size);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid max_body_size: " + value);
                }
            } else if (key.startsWith("error_page")) {
                String[] keyParts = key.split("\\s+");
                if (keyParts.length == 2) {
                    try {
                        int statusCode = Integer.parseInt(keyParts[1]);
                        if (statusCode < 100 || statusCode > 599) {
                            throw new IOException("Invalid error page status code: " + statusCode);
                        }
                        config.setErrorPage(statusCode, value);
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid error page status code: " + keyParts[1]);
                    }
                }
            } else if (key.equalsIgnoreCase("directory_listing")) {
                if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1")) {
                    config.setDirectoryListing(true);
                } else if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false") || value.equals("0")) {
                    config.setDirectoryListing(false);
                } else {
                    throw new IOException("Invalid directory_listing value: " + value + " (must be on/off)");
                }
            }
        }

        if (ports.isEmpty()) {
            ports.add(DEFAULT_PORT);
        }

        if (documentRoot == null || documentRoot.isEmpty()) {
            documentRoot = DEFAULT_DOCUMENT_ROOT;
        }

        Path resolvedRoot = Paths.get(documentRoot).toAbsolutePath().normalize();
        if (!Files.exists(resolvedRoot)) {
            throw new IOException("Document root does not exist: " + resolvedRoot);
        }
        if (!Files.isDirectory(resolvedRoot)) {
            throw new IOException("Document root is not a directory: " + resolvedRoot);
        }

        config.setPorts(ports);
        config.setDocumentRoot(resolvedRoot.toString());
        config.setServerName("default");
        config.addServerName("*");
        config.setDefaultServer(true);

        // Resolve error page paths relative to project directory
        Path projectDir = Paths.get(".").toAbsolutePath().normalize();
        for (Map.Entry<Integer, String> entry : config.getErrorPages().entrySet()) {
            Path errorPath = Paths.get(entry.getValue());
            if (!errorPath.isAbsolute()) {
                errorPath = projectDir.resolve(errorPath).toAbsolutePath().normalize();
            } else {
                errorPath = errorPath.toAbsolutePath().normalize();
            }
            config.setErrorPage(entry.getKey(), errorPath.toString());
        }

        // Parse routes if any defined
        for (String routeVal : rawRoutes) {
            parseRouteDirective(routeVal, config);
        }

        return config;
    }

    private static ServerConfigurations parseServerBlocks(List<String> lines, String configPath) throws IOException {
        ServerConfigurations configurations = new ServerConfigurations();
        int lineNum = 0;
        int blockStart = -1;
        int blockEnd = -1;
        int serverIndex = 0;

        // Find server blocks
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            lineNum = i + 1;

            if (line.contains("#")) {
                line = line.substring(0, line.indexOf('#')).trim();
            }

            if (line.isEmpty()) {
                continue;
            }

            if (line.equals("server") || line.equals("server {") || line.startsWith("server{") || line.startsWith("server {")) {
                if (blockStart >= 0) {
                    throw new IOException("Nested server blocks not allowed at line " + lineNum);
                }
                blockStart = i;
            } else if (line.equals("{")) {
                if (blockStart < 0) {
                    throw new IOException("Unexpected opening brace at line " + lineNum);
                }
            } else if (line.equals("}") || line.endsWith("}")) {
                if (blockStart < 0) {
                    throw new IOException("Unmatched closing brace at line " + lineNum);
                }
                blockEnd = i;
                ServerConfig config = parseServerBlock(lines, blockStart, blockEnd, configPath, serverIndex);
                configurations.addServerConfig(config);
                blockStart = -1;
                blockEnd = -1;
                serverIndex++;
            } else if (blockStart < 0) {
                // Line outside server block
                throw new IOException("Directive outside server block at line " + lineNum + ": " + line);
            }
        }

        if (blockStart >= 0) {
            throw new IOException("Unclosed server block starting at line " + (blockStart + 1));
        }

        // Validate configurations
        validateConfigurations(configurations);

        return configurations;
    }

    private static ServerConfig parseServerBlock(List<String> lines, int start, int end, String configPath, int serverIndex) throws IOException {
        ServerConfig config = new ServerConfig();
        List<Integer> ports = new ArrayList<>();
        String documentRoot = null;
        String serverName = null;
        Set<String> serverNames = new HashSet<>();
        boolean isDefault = false;
        boolean nameExplicitlySet = false;
        List<String> rawRoutes = new ArrayList<>();

        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Strip inline comment
            if (line.contains("#")) {
                line = line.substring(0, line.indexOf('#')).trim();
                if (line.isEmpty()) {
                    continue;
                }
            }

            // Strip trailing semicolon
            if (line.endsWith(";")) {
                line = line.substring(0, line.length() - 1).trim();
            }

            if (line.equals("default_server")) {
                isDefault = true;
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                if (parts[0].equalsIgnoreCase("default_server")) {
                    isDefault = true;
                } else if (parts[0].equalsIgnoreCase("route")) {
                    throw new IOException("Malformed route directive: missing arguments");
                }
                continue;
            }

            String key = parts[0].trim();
            String value = parts[1].trim();

            if (key.equalsIgnoreCase("listen")) {
                if (value.toLowerCase().contains("default_server")) {
                    isDefault = true;
                    value = value.replaceAll("(?i)\\bdefault_server\\b", "").trim();
                }

                String[] portTokens = value.split("\\s+");
                for (String token : portTokens) {
                    token = token.trim();
                    if (token.isEmpty()) continue;
                    try {
                        int port = Integer.parseInt(token);
                        if (port < 1 || port > 65535) {
                            throw new IOException("Invalid port number: " + port + " (must be 1-65535)");
                        }
                        if (!ports.contains(port)) {
                            ports.add(port);
                        }
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid port number: " + token);
                    }
                }
            } else if (key.equalsIgnoreCase("server_name")) {
                String[] names = value.split("\\s+");
                for (String nameToken : names) {
                    nameToken = normalizeServerNameToken(nameToken);
                    if (!nameToken.isEmpty()) {
                        serverNames.add(nameToken);
                        if (serverName == null) {
                            serverName = nameToken;
                        }
                    }
                }
            } else if (key.equalsIgnoreCase("name")) {
                serverName = value.trim();
                nameExplicitlySet = true;
            } else if (key.equalsIgnoreCase("document_root")) {
                if (value.startsWith("=")) {
                    value = value.substring(1).trim();
                }
                documentRoot = value;
            } else if (key.equalsIgnoreCase("max_body_size")) {
                if (value.startsWith("=")) {
                    value = value.substring(1).trim();
                }
                try {
                    int size = parseSize(value);
                    if (size <= 0) {
                        throw new IOException("Invalid max_body_size: " + value + " (must be positive)");
                    }
                    config.setMaxBodySize(size);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid max_body_size: " + value);
                }
            } else if (key.equalsIgnoreCase("directory_listing")) {
                if (value.startsWith("=")) {
                    value = value.substring(1).trim();
                }
                if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1")) {
                    config.setDirectoryListing(true);
                } else if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false") || value.equals("0")) {
                    config.setDirectoryListing(false);
                } else {
                    throw new IOException("Invalid directory_listing value: " + value + " (must be on/off)");
                }
            } else if (key.equalsIgnoreCase("default_server")) {
                isDefault = true;
            } else if (key.equalsIgnoreCase("index")) {
                if (value.startsWith("=")) {
                    value = value.substring(1).trim();
                }
                config.setIndexFile(value);
            } else if (key.equalsIgnoreCase("error_page") || key.startsWith("error_page")) {
                parseErrorPageDirective(key, value, config);
            } else if (key.equalsIgnoreCase("route")) {
                rawRoutes.add(value);
            }
        }

        if (ports.isEmpty()) {
            throw new IOException("Server block missing 'listen' directive");
        }

        if (documentRoot == null || documentRoot.isEmpty()) {
            documentRoot = DEFAULT_DOCUMENT_ROOT;
        }

        Path resolvedRoot = Paths.get(documentRoot).toAbsolutePath().normalize();
        if (!Files.exists(resolvedRoot)) {
            throw new IOException("Document root does not exist: " + resolvedRoot);
        }
        if (!Files.isDirectory(resolvedRoot)) {
            throw new IOException("Document root is not a directory: " + resolvedRoot);
        }

        config.setPorts(ports);
        config.setDocumentRoot(resolvedRoot.toString());
        config.setDefaultServer(isDefault);

        // Set server name
        if (serverName != null) {
            config.setServerName(serverName);
        } else {
            config.setServerName("server_" + ports.get(0) + "_" + serverIndex);
        }

        for (String name : serverNames) {
            config.addServerName(name);
        }

        // Resolve error page paths relative to project directory
        Path projectDir = Paths.get(".").toAbsolutePath().normalize();
        for (Map.Entry<Integer, String> entry : config.getErrorPages().entrySet()) {
            Path errorPath = Paths.get(entry.getValue());
            if (!errorPath.isAbsolute()) {
                errorPath = projectDir.resolve(errorPath).toAbsolutePath().normalize();
            } else {
                errorPath = errorPath.toAbsolutePath().normalize();
            }
            config.setErrorPage(entry.getKey(), errorPath.toString());
        }

        // Parse routes for this server
        for (String routeVal : rawRoutes) {
            parseRouteDirective(routeVal, config);
        }

        return config;
    }

    private static void parseRouteDirective(String value, ServerConfig config) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Malformed route directive: missing arguments");
        }

        String[] tokens = value.trim().split("\\s+");
        if (tokens.length < 2) {
            throw new IOException("Malformed route directive '" + value + "' (expected 'route <path> <action/dir>')");
        }

        String routePath = tokens[0].trim();
        if (!routePath.startsWith("/")) {
            throw new IOException("Invalid route path '" + routePath + "' (must start with '/')");
        }

        // Check for duplicate route path in this server block
        for (Route existing : config.getRoutes()) {
            if (existing.getPath().equals(routePath)) {
                throw new IOException("Duplicate route path '" + routePath + "' in server " + config.getServerName());
            }
        }

        String actionOrDir = tokens[1].trim();
        String type = "root";
        String docRoot = null;
        List<String> methods = new ArrayList<>();

        if (actionOrDir.equalsIgnoreCase("root") || actionOrDir.equalsIgnoreCase("static")) {
            type = "root";
            if (tokens.length > 2 && !isHttpMethod(tokens[2])) {
                docRoot = tokens[2];
                for (int i = 3; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            } else {
                for (int i = 2; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            }
        } else if (actionOrDir.equalsIgnoreCase("upload")) {
            type = "upload";
            if (tokens.length > 2 && !isHttpMethod(tokens[2])) {
                docRoot = tokens[2];
                for (int i = 3; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            } else {
                for (int i = 2; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            }
        } else if (actionOrDir.equalsIgnoreCase("cgi")) {
            type = "cgi";
            if (tokens.length > 2 && !isHttpMethod(tokens[2])) {
                docRoot = tokens[2];
                for (int i = 3; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            } else {
                for (int i = 2; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            }
        } else {
            // Check if actionOrDir looks like a directory path
            Path projectDir = Paths.get(".").toAbsolutePath().normalize();
            Path checkPath = Paths.get(actionOrDir);
            if (!checkPath.isAbsolute()) {
                checkPath = projectDir.resolve(checkPath).toAbsolutePath().normalize();
            }
            if (Files.exists(checkPath) || actionOrDir.contains("/") || actionOrDir.contains("\\") || actionOrDir.startsWith(".")) {
                type = "root";
                docRoot = actionOrDir;
                for (int i = 2; i < tokens.length; i++) {
                    extractMethods(tokens[i], methods);
                }
            } else {
                throw new IOException("Invalid route action or directory '" + actionOrDir + "'");
            }
        }

        // Resolve document root
        Path projectDir = Paths.get(".").toAbsolutePath().normalize();
        Path resolvedDir;
        if (docRoot != null) {
            resolvedDir = Paths.get(docRoot);
            if (!resolvedDir.isAbsolute()) {
                resolvedDir = projectDir.resolve(resolvedDir).toAbsolutePath().normalize();
            } else {
                resolvedDir = resolvedDir.normalize();
            }
            if (!Files.exists(resolvedDir)) {
                throw new IOException("Route document root does not exist: " + resolvedDir);
            }
            if (!Files.isDirectory(resolvedDir)) {
                throw new IOException("Route document root is not a directory: " + resolvedDir);
            }
        } else {
            // Default based on server's document root
            String serverRoot = config.getDocumentRoot();
            if (serverRoot == null) {
                serverRoot = DEFAULT_DOCUMENT_ROOT;
            }
            Path base = Paths.get(serverRoot).toAbsolutePath().normalize();
            if (type.equals("upload")) {
                Path uPath = base.resolve("uploads");
                resolvedDir = Files.exists(uPath) ? uPath : base;
            } else if (type.equals("cgi")) {
                Path cPath = base.resolve("cgi-bin");
                resolvedDir = Files.exists(cPath) ? cPath : base;
            } else {
                // Static route: check if base has subfolder matching routePath
                String sub = routePath.startsWith("/") ? routePath.substring(1) : routePath;
                Path subPath = base.resolve(sub);
                resolvedDir = (Files.exists(subPath) && Files.isDirectory(subPath)) ? subPath : base;
            }
        }

        Route route = new Route(routePath, resolvedDir.toString(), type);
        for (String m : methods) {
            route.addAcceptedMethod(m);
        }
        if (type.equals("upload") && methods.isEmpty()) {
            route.addAcceptedMethod("POST");
        }

        config.addRoute(route);
    }

    private static boolean isHttpMethod(String token) {
        if (token == null) return false;
        String upper = token.toUpperCase();
        return upper.equals("GET") || upper.equals("POST") || upper.equals("DELETE") || 
               upper.equals("HEAD") || upper.equals("PUT") || upper.startsWith("METHODS=");
    }

    private static void extractMethods(String token, List<String> methods) {
        if (token.toUpperCase().startsWith("METHODS=")) {
            token = token.substring(8);
        }
        for (String part : token.split("[,;\\|]")) {
            part = part.trim().toUpperCase();
            if (!part.isEmpty()) {
                methods.add(part);
            }
        }
    }

    private static String normalizeServerNameToken(String token) {
        token = token.trim();
        int colon = token.indexOf(':');
        if (colon >= 0) {
            token = token.substring(0, colon);
        }
        return token.toLowerCase().trim();
    }

    private static void parseErrorPageDirective(String key, String value, ServerConfig config) throws IOException {
        int statusCode = -1;
        String path = null;

        if (key.equalsIgnoreCase("error_page")) {
            String normalized = value.replace('=', ' ').trim();
            String[] parts = normalized.split("\\s+", 2);
            if (parts.length == 2) {
                try {
                    statusCode = Integer.parseInt(parts[0].trim());
                    path = parts[1].trim();
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid error page status code: " + parts[0]);
                }
            }
        } else {
            String[] keyParts = key.split("\\s+");
            if (keyParts.length == 2) {
                try {
                    statusCode = Integer.parseInt(keyParts[1].trim());
                    path = value.replace('=', ' ').trim();
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid error page status code: " + keyParts[1]);
                }
            }
        }

        if (statusCode >= 100 && statusCode <= 599 && path != null && !path.isEmpty()) {
            config.setErrorPage(statusCode, path);
        } else if (statusCode != -1) {
            throw new IOException("Invalid error page status code: " + statusCode);
        }
    }

    private static void validateConfigurations(ServerConfigurations configurations) throws IOException {
        List<ServerConfig> allConfigs = configurations.getAllConfigs();
        List<Integer> allPorts = configurations.getAllPorts();

        // Check for duplicate explicit server names (identifiers)
        Set<String> explicitServerNames = new HashSet<>();
        for (ServerConfig config : allConfigs) {
            String name = config.getServerName();
            if (name != null && !name.startsWith("server_")) {
                if (!explicitServerNames.add(name.toLowerCase())) {
                    throw new IOException("Duplicate server name: " + name);
                }
            }
        }

        // Check each port for conflicting server_name and default server count
        for (Integer port : allPorts) {
            List<ServerConfig> configsForPort = configurations.getConfigsForPort(port);

            // Check for duplicate default servers per port
            ServerConfig explicitDefault = null;
            for (ServerConfig config : configsForPort) {
                if (config.isDefaultServer()) {
                    if (explicitDefault != null) {
                        throw new IOException("Multiple default servers configured for port " + port);
                    }
                    explicitDefault = config;
                }
            }

            if (explicitDefault != null) {
                configurations.setDefaultServerForPort(port, explicitDefault);
            } else if (!configsForPort.isEmpty()) {
                // Deterministic fallback to first declared server
                configurations.setDefaultServerForPort(port, configsForPort.get(0));
            }

            // Check for conflicting server_name configured on the same port
            Set<String> namesOnPort = new HashSet<>();
            for (ServerConfig config : configsForPort) {
                for (String sName : config.getServerNames()) {
                    if (!sName.equals("*")) {
                        if (!namesOnPort.add(sName)) {
                            throw new IOException("Conflicting server_name '" + sName + "' configured for port " + port);
                        }
                    }
                }
            }
        }
    }

    private static int parseSize(String value) {
        value = value.toLowerCase().trim();
        if (value.endsWith("k") || value.endsWith("kb")) {
            return Integer.parseInt(value.replaceAll("[kkmgb]", "")) * 1024;
        } else if (value.endsWith("m") || value.endsWith("mb")) {
            return Integer.parseInt(value.replaceAll("[kkmgb]", "")) * 1024 * 1024;
        } else if (value.endsWith("g") || value.endsWith("gb")) {
            return Integer.parseInt(value.replaceAll("[kkmgb]", "")) * 1024 * 1024 * 1024;
        }
        return Integer.parseInt(value);
    }
}
