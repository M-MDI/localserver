package config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Container for multiple server configurations.
 * Maps ports to the list of ServerConfig instances that listen on that port.
 */
public class ServerConfigurations {
    private final List<ServerConfig> allConfigs;
    private final Map<Integer, List<ServerConfig>> portToConfigs;
    private final Map<Integer, ServerConfig> defaultServerByPort;

    public ServerConfigurations() {
        this.allConfigs = new ArrayList<>();
        this.portToConfigs = new HashMap<>();
        this.defaultServerByPort = new HashMap<>();
    }

    public void addServerConfig(ServerConfig config) {
        allConfigs.add(config);
        for (Integer port : config.getPorts()) {
            portToConfigs.computeIfAbsent(port, k -> new ArrayList<>()).add(config);
        }
    }

    public List<ServerConfig> getAllConfigs() {
        return allConfigs;
    }

    public List<ServerConfig> getConfigsForPort(int port) {
        return portToConfigs.getOrDefault(port, new ArrayList<>());
    }

    public void setDefaultServerForPort(int port, ServerConfig config) {
        defaultServerByPort.put(port, config);
    }

    public ServerConfig getDefaultServer(int port) {
        ServerConfig defaultConfig = defaultServerByPort.get(port);
        if (defaultConfig != null) {
            return defaultConfig;
        }
        List<ServerConfig> configs = getConfigsForPort(port);
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        return null;
    }

    public ServerConfig selectServerConfig(int port, String hostHeader) {
        List<ServerConfig> configsForPort = getConfigsForPort(port);
        if (configsForPort.isEmpty()) {
            return null;
        }

        // Normalize host header (remove port if present, convert to lowercase)
        String normalizedHost = normalizeHostHeader(hostHeader);

        // Try to match by server_name
        if (!normalizedHost.isEmpty()) {
            for (ServerConfig config : configsForPort) {
                if (config.getServerNames().contains(normalizedHost)) {
                    return config;
                }
            }

            // Check wildcard matching
            for (ServerConfig config : configsForPort) {
                if (config.getServerNames().contains("*")) {
                    return config;
                }
            }
        }

        // No match, return default server for this port
        ServerConfig defaultConfig = defaultServerByPort.get(port);
        if (defaultConfig != null) {
            return defaultConfig;
        }

        // Deterministic fallback to first server on this port
        return configsForPort.get(0);
    }

    private String normalizeHostHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return "";
        }

        String host = hostHeader.trim();

        // Handle IPv6 literal with port, e.g., "[::1]:8080"
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            if (closingBracket > 0) {
                host = host.substring(0, closingBracket + 1);
            }
        } else {
            // Remove port if present (e.g., "localhost:8080" -> "localhost")
            int colonIndex = host.indexOf(':');
            if (colonIndex >= 0) {
                host = host.substring(0, colonIndex);
            }
        }

        return host.toLowerCase().trim();
    }

    public List<Integer> getAllPorts() {
        return new ArrayList<>(portToConfigs.keySet());
    }
}
