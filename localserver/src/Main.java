public class Main {
    public static void main(String[] args) {
        try {
            int[] ports = {8080, 8081};
            HttpServer server = new HttpServer(ports);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
