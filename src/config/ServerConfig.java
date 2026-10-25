package config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import router.Route;

public class ServerConfig {
    private List<Integer> ports;
    private String documentRoot;
    private int maxBodySize;
    private Map<Integer, String> errorPages;
    private boolean directoryListing;
    private String serverName;
    private Set<String> serverNames;
    private boolean isDefaultServer;
    private String indexFile;
    private List<Route> routes;
    private List<Route> redirects;

    public ServerConfig() {
        this.ports = new ArrayList<>();
        this.maxBodySize = 10 * 1024 * 1024; // 10 MB default
        this.errorPages = new HashMap<>();
        this.directoryListing = false; // off by default
        this.serverNames = new HashSet<>();
        this.isDefaultServer = false;
        this.indexFile = "index.html";
        this.routes = new ArrayList<>();
        this.redirects = new ArrayList<>();
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public void setPorts(List<Integer> ports) {
        this.ports = ports;
    }

    public void addPort(int port) {
        if (!this.ports.contains(port)) {
            this.ports.add(port);
        }
    }

    public String getDocumentRoot() {
        return documentRoot;
    }

    public void setDocumentRoot(String documentRoot) {
        this.documentRoot = documentRoot;
    }

    public int getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(int maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public Map<Integer, String> getErrorPages() {
        return errorPages;
    }

    public void setErrorPage(int statusCode, String path) {
        errorPages.put(statusCode, path);
    }

    public boolean isDirectoryListing() {
        return directoryListing;
    }

    public void setDirectoryListing(boolean directoryListing) {
        this.directoryListing = directoryListing;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public Set<String> getServerNames() {
        return serverNames;
    }

    public void addServerName(String serverName) {
        if (serverName != null && !serverName.trim().isEmpty()) {
            this.serverNames.add(serverName.trim().toLowerCase());
        }
    }

    public boolean isDefaultServer() {
        return isDefaultServer;
    }

    public void setDefaultServer(boolean defaultServer) {
        isDefaultServer = defaultServer;
    }

    public String getIndexFile() {
        return indexFile;
    }

    public void setIndexFile(String indexFile) {
        this.indexFile = indexFile;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void addRoute(Route route) {
        this.routes.add(route);
    }

    public boolean hasRoutes() {
        return routes != null && !routes.isEmpty();
    }

    public List<Route> getRedirects() {
        return redirects;
    }

    public void addRedirect(Route redirect) {
        this.redirects.add(redirect);
    }

    public boolean hasRedirects() {
        return redirects != null && !redirects.isEmpty();
    }
}
