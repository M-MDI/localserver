import server.HttpServer;
import config.ConfigLoader;
import config.ServerConfig;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            ServerConfig config = ConfigLoader.load();
            List<Integer> portList = config.getPorts();
            int[] ports = portList.stream().mapToInt(i -> i).toArray();
            String documentRoot = config.getDocumentRoot();
            
            HttpServer server = new HttpServer(ports, documentRoot);
            server.start();
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
