package config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerConfig {
    private List<Integer> ports;
    private String documentRoot;
    private int maxBodySize;
    private Map<Integer, String> errorPages;

    public ServerConfig() {
        this.ports = new ArrayList<>();
        this.maxBodySize = 10 * 1024 * 1024; // 10 MB default
        this.errorPages = new HashMap<>();
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
}
