package session;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private static final int SESSION_ID_LENGTH = 32;
    private static final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions;
    private static final String SESSION_COOKIE_NAME = "SESSIONID";

    public SessionManager() {
        this.sessions = new ConcurrentHashMap<>();
    }

    public Session createSession() {
        String sessionId = generateSessionId();
        Session session = new Session(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    public Session getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }

        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }

        if (session.isExpired()) {
            sessions.remove(sessionId);
            return null;
        }

        session.setLastAccessedTime(System.currentTimeMillis());
        return session;
    }

    public Session getOrCreateSession(String sessionId) {
        Session session = getSession(sessionId);
        if (session == null) {
            session = createSession();
        }
        return session;
    }

    public void invalidateSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.invalidate();
        }
    }

    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    private String generateSessionId() {
        byte[] bytes = new byte[SESSION_ID_LENGTH];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String getSessionCookieName() {
        return SESSION_COOKIE_NAME;
    }
}