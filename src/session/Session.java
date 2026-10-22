package session;

import java.util.HashMap;
import java.util.Map;

public class Session {
    private final String sessionId;
    private final Map<String, Object> attributes;
    private final long creationTime;
    private long lastAccessedTime;
    private long maxInactiveInterval;

    public Session(String sessionId) {
        this.sessionId = sessionId;
        this.attributes = new HashMap<>();
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = this.creationTime;
        this.maxInactiveInterval = 30 * 60 * 1000; // 30 minutes default
    }

    public String getSessionId() {
        return sessionId;
    }

    public Object getAttribute(String name) {
        lastAccessedTime = System.currentTimeMillis();
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        lastAccessedTime = System.currentTimeMillis();
        attributes.put(name, value);
    }

    public void removeAttribute(String name) {
        lastAccessedTime = System.currentTimeMillis();
        attributes.remove(name);
    }

    public Map<String, Object> getAttributes() {
        lastAccessedTime = System.currentTimeMillis();
        return new HashMap<>(attributes);
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }

    public void setLastAccessedTime(long lastAccessedTime) {
        this.lastAccessedTime = lastAccessedTime;
    }

    public long getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    public void setMaxInactiveInterval(long maxInactiveInterval) {
        this.maxInactiveInterval = maxInactiveInterval;
    }

    public boolean isExpired() {
        long now = System.currentTimeMillis();
        return (now - lastAccessedTime) > maxInactiveInterval;
    }

    public void invalidate() {
        attributes.clear();
    }
}