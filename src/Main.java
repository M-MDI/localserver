import server.HttpServer;
import config.ConfigLoader;
import config.ServerConfig;
import http.HttpParser;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            ServerConfig config = ConfigLoader.load();
            List<Integer> portList = config.getPorts();
            int[] ports = portList.stream().mapToInt(i -> i).toArray();
            String documentRoot = config.getDocumentRoot();
            
            HttpParser.setMaxBodySize(config.getMaxBodySize());
            
            System.out.println("Configuration loaded:");
            System.out.println("  Ports: " + portList);
            System.out.println("  Document root: " + documentRoot);
            System.out.println("  Max body size: " + config.getMaxBodySize() + " bytes");
            System.out.println("  Error pages: " + config.getErrorPages());
            
            HttpServer server = new HttpServer(ports, documentRoot, config);
            server.start();
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
