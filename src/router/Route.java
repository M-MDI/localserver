package router;

public class Route {
    private final String path;
    private final String documentRoot;

    public Route(String path, String documentRoot) {
        this.path = path;
        this.documentRoot = documentRoot;
    }

    public String getPath() {
        return path;
    }

    public String getDocumentRoot() {
        return documentRoot;
    }

    public boolean matches(String requestPath) {
        if (path.equals("/")) {
            return true;
        }
        return requestPath.startsWith(path);
    }
}
