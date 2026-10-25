package router;

import java.util.HashSet;
import java.util.Set;

public class Route {
    private final String path;
    private final String documentRoot;
    private final String type; // "root", "static", "upload", "cgi"
    private final Set<String> acceptedMethods;
    private String redirectPath;

    public Route(String path, String documentRoot) {
        this(path, documentRoot, "root");
    }

    public Route(String path, String documentRoot, String type) {
        String p = (path == null) ? "/" : path.trim();
        this.path = (p.length() > 1 && p.endsWith("/")) 
            ? p.substring(0, p.length() - 1) 
            : p;
        this.documentRoot = documentRoot;
        this.type = (type != null) ? type.toLowerCase() : "root";
        this.acceptedMethods = new HashSet<>();
    }

    public String getPath() {
        return path;
    }

    public String getDocumentRoot() {
        return documentRoot;
    }

    public String getType() {
        return type;
    }

    public boolean isUpload() {
        return "upload".equalsIgnoreCase(type);
    }

    public boolean isCgi() {
        return "cgi".equalsIgnoreCase(type);
    }

    public boolean isStatic() {
        return "root".equalsIgnoreCase(type) || "static".equalsIgnoreCase(type);
    }

    public void addAcceptedMethod(String method) {
        acceptedMethods.add(method.toUpperCase());
    }

    public Set<String> getAcceptedMethods() {
        return acceptedMethods;
    }

    public boolean isMethodAccepted(String method) {
        if (method == null) {
            return false;
        }
        if (acceptedMethods.isEmpty()) {
            return true; // If no methods are specified, accept all
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
        if (requestPath == null) {
            return false;
        }
        String cleanPath = requestPath;
        int queryIndex = cleanPath.indexOf('?');
        if (queryIndex >= 0) {
            cleanPath = cleanPath.substring(0, queryIndex);
        }

        if (cleanPath.isEmpty()) {
            cleanPath = "/";
        }

        if (path.equals("/")) {
            return cleanPath.startsWith("/");
        }

        if (cleanPath.equals(path)) {
            return true;
        }

        String prefix = path.endsWith("/") ? path : path + "/";
        return cleanPath.startsWith(prefix);
    }
}
