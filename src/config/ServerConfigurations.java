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

    public ServerConfigurations() {
        this.allConfigs = new ArrayList<>();
        this.portToConfigs = new HashMap<>();
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

    public ServerConfig selectServerConfig(int port, String hostHeader) {
        List<ServerConfig> configsForPort = getConfigsForPort(port);
        if (configsForPort.isEmpty()) {
            return null;
        }

        // Normalize host header (remove port if present, convert to lowercase)
        String normalizedHost = normalizeHostHeader(hostHeader);

        // Try to match by server_name
        for (ServerConfig config : configsForPort) {
            if (config.getServerNames().contains(normalizedHost)) {
                return config;
            }
        }

        // No match, try default server
        for (ServerConfig config : configsForPort) {
            if (config.isDefaultServer()) {
                return config;
            }
        }

        // No default server, use first one (deterministic fallback)
        return configsForPort.get(0);
    }

    private String normalizeHostHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return "";
        }

        // Remove port if present (e.g., "localhost:8080" -> "localhost")
        int colonIndex = hostHeader.indexOf(':');
        if (colonIndex > 0) {
            hostHeader = hostHeader.substring(0, colonIndex);
        }

        return hostHeader.toLowerCase().trim();
    }

    public List<Integer> getAllPorts() {
        return new ArrayList<>(portToConfigs.keySet());
    }
}
