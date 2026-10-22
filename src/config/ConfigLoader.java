package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigLoader {
    private static final String DEFAULT_CONFIG_PATH = "config/server.conf";
    private static final int DEFAULT_PORT = 65432;
    private static final String DEFAULT_DOCUMENT_ROOT = "./www";
    private static final int DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024; // 10 MB

    public static ServerConfig load() throws IOException {
        return load(DEFAULT_CONFIG_PATH);
    }

    public static ServerConfig load(String configPath) throws IOException {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + configPath);
        }

        List<String> lines = Files.readAllLines(path);
        ServerConfig config = new ServerConfig();
        List<Integer> ports = new ArrayList<>();
        String documentRoot = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
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

        return config;
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
