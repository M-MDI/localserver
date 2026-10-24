import server.HttpServer;
import config.ConfigLoader;
import config.ServerConfig;
import config.ServerConfigurations;
import http.HttpParser;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = (args != null && args.length > 0) ? args[0] : "config/server.conf";
            ServerConfigurations configurations = ConfigLoader.loadMultiServer(configPath);

            if (configurations.getAllConfigs().size() == 1) {
                // Single-server mode (backward compatibility)
                ServerConfig config = configurations.getAllConfigs().get(0);
                List<Integer> portList = config.getPorts();
                int[] ports = portList.stream().mapToInt(i -> i).toArray();
                String documentRoot = config.getDocumentRoot();

                HttpParser.setMaxBodySize(config.getMaxBodySize());

                System.out.println("Configuration loaded (single-server mode):");
                System.out.println("  Ports: " + portList);
                System.out.println("  Document root: " + documentRoot);
                System.out.println("  Max body size: " + config.getMaxBodySize() + " bytes");
                System.out.println("  Error pages: " + config.getErrorPages());

                HttpServer server = new HttpServer(ports, documentRoot, config);
                server.start();
            } else {
                // Multi-server mode
                System.out.println("Configuration loaded (multi-server mode):");
                System.out.println("  Total servers: " + configurations.getAllConfigs().size());
                System.out.println("  Ports: " + configurations.getAllPorts());

                for (ServerConfig config : configurations.getAllConfigs()) {
                    System.out.println("  Server: " + config.getServerName());
                    System.out.println("    Listening on: " + config.getPorts());
                    System.out.println("    Document root: " + config.getDocumentRoot());
                    System.out.println("    Server names: " + config.getServerNames());
                    System.out.println("    Default: " + config.isDefaultServer());
                    System.out.println("    Max body size: " + config.getMaxBodySize() + " bytes");
                }

                HttpServer server = new HttpServer(configurations);
                server.start();
            }
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
