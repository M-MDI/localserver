package config;

import java.util.List;

public class ServerConfig {
    private List<Integer> ports;
    private String documentRoot;

    public ServerConfig(List<Integer> ports, String documentRoot) {
        this.ports = ports;
        this.documentRoot = documentRoot;
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
}
