package router;

import java.util.HashSet;
import java.util.Set;

public class Route {
    private final String path;
    private final String documentRoot;
    private final Set<String> acceptedMethods;
    private String redirectPath;

    public Route(String path, String documentRoot) {
        this.path = path;
        this.documentRoot = documentRoot;
        this.acceptedMethods = new HashSet<>();
    }

    public String getPath() {
        return path;
    }

    public String getDocumentRoot() {
        return documentRoot;
    }

    public void addAcceptedMethod(String method) {
        acceptedMethods.add(method.toUpperCase());
    }

    public boolean isMethodAccepted(String method) {
        if (acceptedMethods.isEmpty()) {
            return true; // If no methods are specified, accept all (or maybe change to false if strict)
        }
        return acceptedMethods.contains(method.toUpperCase());
    }

    public void setRedirectPath(String redirectPath) {
        this.redirectPath = redirectPath;
    }

    public String getRedirectPath() {
        return redirectPath;
    }

    public boolean isRedirect() {
        return redirectPath != null && !redirectPath.isEmpty();
    }

    public boolean matches(String requestPath) {
        if (path.equals("/")) {
            return true;
        }
        return requestPath.startsWith(path);
    }
}
