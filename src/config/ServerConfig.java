package config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public ServerConfig() {
        this.ports = new ArrayList<>();
        this.maxBodySize = 10 * 1024 * 1024; // 10 MB default
        this.errorPages = new HashMap<>();
        this.directoryListing = false; // off by default
        this.serverNames = new HashSet<>();
        this.isDefaultServer = false;
        this.indexFile = "index.html";
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public void setPorts(List<Integer> ports) {
        this.ports = ports;
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
        this.serverNames.add(serverName.toLowerCase());
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
}
