import server.HttpServer;

public class Main {
    public static void main(String[] args) {
        try {
            int[] ports = {65432};
            HttpServer server = new HttpServer(ports);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
